/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.expression.Cast;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import io.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

// TODO  This should be a KernelProducerComputationAdapter subclass
public class PackedCollectionMap<T extends PackedCollection<?>>
		extends CollectionProducerComputationBase<PackedCollection<?>, T>
		implements TraversableExpression<Double> {
	public static boolean enableAtomicKernel = false;
	public static boolean enableChainDelta = false;

	private Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper;
	private TraversableExpression<Double> mapped;
	private TraversalPolicy inputShape;

	private boolean ignoreTraversalAxis;

	public PackedCollectionMap(Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		this(shape(collection), collection, mapper);
	}

	public PackedCollectionMap(TraversalPolicy shape, Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		super(null, shape, (Supplier) collection);
		this.inputShape = shape(collection);
		this.mapper = mapper;

		if (inputShape.getTraversalAxis() != shape.getTraversalAxis()) {
			throw new IllegalArgumentException("Input and output shapes must have the same traversal axis");
		}
	}

	public boolean isIgnoreTraversalAxis() {
		return ignoreTraversalAxis;
	}

	public void setIgnoreTraversalAxis(boolean ignoreTraversalAxis) {
		this.ignoreTraversalAxis = ignoreTraversalAxis;
	}

	@Override
	public int getMemLength() {
		return enableAtomicKernel ? 1 : isIgnoreTraversalAxis() ? getShape().getTotalSize() : super.getMemLength();
	}

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		Scope<T> scope = super.getScope(context);

		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		for (int i = 0; i < getMemLength(); i++) {
			Expression index = new KernelIndex();
			if (getMemLength() > 1) index = index.multiply(getMemLength()).add(i);

			Expression<Double> value = getValueAt(index);

			if (value == null && mapped instanceof OperationAdapter) {
				OperationAdapter<?, ?> op = (OperationAdapter) mapped;
				Supplier in = op.getInputs().get(0);
				ArrayVariable v = op.getArgumentForInput(in);
				value = v.referenceRelative(e(i));
			}

			if (value == null) throw new UnsupportedOperationException();

			scope.getVariables().add(output.referenceRelative(i).assign(value));
		}

		return scope;
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		ArrayVariable arg = getArgumentForInput(getInputs().get(1));
		if (arg instanceof CollectionVariable == false) {
			throw new IllegalArgumentException("Map input must be a collection");
		}

		CollectionVariable input = (CollectionVariable) arg;

		TraversalPolicy sliceShape = inputShape.item();
		TraversalPolicy traversalShape = new TraversalPolicy();
		int traversalDimensions = inputShape.getDimensions() - sliceShape.getDimensions();
		for (int i = 0; i < traversalDimensions; i++) {
			sliceShape = sliceShape.prependDimension(1);
			traversalShape = traversalShape.appendDimension(inputShape.length(i));
		}

		CollectionExpression expression = createCollectionExpression(input, sliceShape, traversalShape);
		CollectionProducerComputationBase computation = new ItemComputation(sliceShape, args -> expression);

		CollectionProducerComputation<?> mapped = mapper.apply(computation);

		if (mapped.getShape().getTotalSize() != getShape().getSize()) {
			throw new IllegalArgumentException("Mapping returned " + mapped.getShape() +
					" while attempting to map items to " + getShape().item());
		}

		if (mapped instanceof PackedCollectionMap) {
			warn("Embedded PackedCollectionMap");
			((PackedCollectionMap<?>) mapped).setIgnoreTraversalAxis(true);
		}

		if (mapped instanceof TraversableExpression) {
			ScopeLifecycle.prepareScope(Stream.of(mapped), manager, context);
			this.mapped = (TraversableExpression<Double>) mapped;
		} else {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (mapped != null) {
			Expression<Double> result = mapped.getValueAt(index);
			return result;
		} else {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (!enableChainDelta || !(AlgebraFeatures.deepMatch(getInputs().get(1), target))) {
			return TraversableDeltaComputation.create(getShape(), shape(target),
					args -> CollectionExpression.create(getShape(), idx -> args[1].getValueAt(idx)), target,
					(Supplier) this).addDependentLifecycle(this);
		}

		TraversalPolicy outShape = getShape();
		TraversalPolicy inShape = shape(getInputs().get(1));
		TraversalPolicy targetShape = shape(target);

		int outSize = outShape.getTotalSize();
		int inSize = inShape.getTotalSize();
		int targetSize = targetShape.getTotalSize();

		TraversalPolicy deltaShape = shape(inSize, targetSize);
		TraversalPolicy overallShape = shape(outSize, targetSize);

		Producer<?> stub = func(inShape, args -> null);

		TraversableDeltaComputation<T> deltaOut = TraversableDeltaComputation.create(shape(outSize), shape(inSize),
				args -> CollectionExpression.create(getShape(), idx -> args[1].getValueAt(idx)),
				stub, (Supplier) new PackedCollectionMap<>(getShape(), stub, mapper));
		Producer deltaIn = ((CollectionProducer<PackedCollection<?>>) getInputs().get(1))
							.delta(target).reshape(shape(inSize, targetSize));
		if (deltaIn instanceof ScopeLifecycle) deltaOut.addDependentLifecycle((ScopeLifecycle) deltaIn);
		return MatrixFeatures.getInstance().mproduct(deltaOut, deltaIn);
	}

	@Override
	public PackedCollectionMap<T> generate(List<Process<?, ?>> children) {
		return new PackedCollectionMap<>(getShape(), (Producer) children.get(1), mapper);
	}

	private CollectionExpression createCollectionExpression(CollectionVariable input, TraversalPolicy sliceShape, TraversalPolicy traversalShape) {
		return DefaultCollectionExpression.create(sliceShape,
				index -> {
					// Determine which slice to extract
					Expression slice;

					if (sliceShape.getTotalSize() == 1) {
						slice = index;
					} else if (index.getType() == Integer.class ||
							(index instanceof Cast && Objects.equals("int", ((Cast) index).getTypeName()))) {
						slice = index.divide(e(sliceShape.getTotalSize()));
					} else {
						slice = index.divide(e((double) sliceShape.getTotalSize())).floor();
					}

					// Find the index in that slice
					Expression offset = index.toInt().mod(e(sliceShape.getTotalSize()), false);
					offset = slice.multiply(e(sliceShape.getTotalSize())).add(offset);
					return input.getValueAt(offset);
				});
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Map cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}

	private static class ItemComputation<T extends PackedCollection<?>> extends DefaultTraversableExpressionComputation<T> {
		public ItemComputation(TraversalPolicy shape,
							   Function<TraversableExpression[], CollectionExpression> expression,
							   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
			super(null, shape, expression, args);
		}

		@Override
		public boolean isFixedCount() {
			return false;
		}
	}
}
