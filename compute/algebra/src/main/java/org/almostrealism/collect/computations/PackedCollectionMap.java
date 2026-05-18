/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.ComputableBase;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A deprecated computation that applies a mapping function to each item of a {@link PackedCollection}.
 * This class transforms each slice of the input collection using the provided mapper function,
 * producing an output collection with the same traversal axis.
 *
 * @deprecated Use {@link TraversableExpressionComputation} or lambda-based approaches instead.
 */
@Deprecated
public class PackedCollectionMap
		extends CollectionProducerComputationBase
		implements TraversableExpression<Double> {
	/** When true, generates a single-element kernel instead of a multi-element kernel. */
	public static boolean enableAtomicKernel = false;

	/** When true, enables chain-rule delta computation for backpropagation through the map. */
	public static boolean enableChainDelta = false;

	/** The function applied to each item of the input collection to produce the output items. */
	private final Function<CollectionProducerComputation, CollectionProducer> mapper;

	/** The evaluated mapped expression, set during scope preparation. */
	private TraversableExpression<Double> mapped;

	/** The shape of the input collection. */
	private final TraversalPolicy inputShape;

	/** When true, the traversal axis is ignored and the full shape is used for memory length. */
	private boolean ignoreTraversalAxis;

	/**
	 * Creates a map computation with the output shape inferred from the input collection.
	 *
	 * @param collection the input collection producer to map over
	 * @param mapper the function that transforms each item of the input collection
	 */
	public PackedCollectionMap(Producer<?> collection, Function<CollectionProducerComputation, CollectionProducer> mapper) {
		this(shape(collection), collection, mapper);
	}

	/**
	 * Creates a map computation with an explicit output shape.
	 *
	 * @param shape the desired output shape; must have the same traversal axis as the input
	 * @param collection the input collection producer to map over
	 * @param mapper the function that transforms each item of the input collection
	 * @throws IllegalArgumentException if the input and output shapes have different traversal axes
	 */
	public PackedCollectionMap(TraversalPolicy shape, Producer<?> collection, Function<CollectionProducerComputation, CollectionProducer> mapper) {
		super("map", shape, (Producer<PackedCollection>) collection);
		this.inputShape = shape(collection);
		this.mapper = mapper;

		if (inputShape.getTraversalAxis() != shape.getTraversalAxis()) {
			throw new IllegalArgumentException("Input and output shapes must have the same traversal axis");
		}
	}

	/**
	 * Returns whether the traversal axis is being ignored for memory length calculation.
	 *
	 * @return true if traversal axis is ignored
	 */
	public boolean isIgnoreTraversalAxis() {
		return ignoreTraversalAxis;
	}

	/**
	 * Sets whether the traversal axis should be ignored for memory length calculation.
	 * When true, the full shape total size is used instead of the default traversal-based length.
	 *
	 * @param ignoreTraversalAxis true to ignore traversal axis
	 */
	public void setIgnoreTraversalAxis(boolean ignoreTraversalAxis) {
		this.ignoreTraversalAxis = ignoreTraversalAxis;
	}

	@Override
	public int getMemLength() {
		return enableAtomicKernel ? 1 : isIgnoreTraversalAxis() ? getShape().getTotalSize() : super.getMemLength();
	}

	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		Scope<PackedCollection> scope = super.getScope(context);

		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		KernelIndex kernel = new KernelIndex(context);

		for (int i = 0; i < getMemLength(); i++) {
			Expression index = kernel;
			if (getMemLength() > 1) index = index.multiply(getMemLength()).add(i);

			Expression<Double> value = getValueAt(index);

			if (value == null && mapped instanceof ComputableBase) {
				ComputableBase<?, ?> op = (ComputableBase) mapped;
				Supplier in = op.getInputs().get(0);
				ArrayVariable v = op.getArgumentForInput(in);
				value = v.reference(index);
			}

			if (value == null) throw new UnsupportedOperationException();

			scope.getVariables().add(output.reference(index).assign(value));
		}

		return scope;
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		ArrayVariable arg = getArgumentForInput(getInputs().get(1));
		if (!(arg instanceof CollectionVariable)) {
			throw new IllegalArgumentException("Map input must be a collection");
		}

		CollectionVariable input = (CollectionVariable) arg;

		TraversalPolicy sliceShape = inputShape.item();
		int traversalDimensions = inputShape.getDimensions() - sliceShape.getDimensions();
		for (int i = 0; i < traversalDimensions; i++) {
			sliceShape = sliceShape.prependDimension(1);
		}

		CollectionExpression expression = createCollectionExpression(input, sliceShape);
		CollectionProducerComputationBase computation = new ItemComputation(sliceShape, args -> expression);

		CollectionProducer mapped = mapper.apply(computation);

		if (mapped.getShape().getTotalSize() != getShape().getSize()) {
			throw new IllegalArgumentException("Mapping returned " + mapped.getShape() +
					" while attempting to map items to " + getShape().item());
		}

		if (mapped instanceof PackedCollectionMap) {
			warn("Embedded PackedCollectionMap");
			((PackedCollectionMap) mapped).setIgnoreTraversalAxis(true);
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
	public CollectionProducer delta(Producer<?> target) {
		if (!enableChainDelta || !(AlgebraFeatures.deepMatch(getInputs().get(1), target))) {
			return TraversableDeltaComputation.create("delta", getShape(), shape(target),
					args -> CollectionExpression.create(getShape(), idx -> args[1].getValueAt(idx)), target,
					this).addDependentLifecycle(this);
		}

		TraversalPolicy outShape = getShape();
		TraversalPolicy inShape = shape(getInputs().get(1));
		TraversalPolicy targetShape = shape(target);

		int outSize = outShape.getTotalSize();
		int inSize = inShape.getTotalSize();
		int targetSize = targetShape.getTotalSize();

		Producer<?> stub = func(inShape, args -> null);

		TraversableDeltaComputation deltaOut = TraversableDeltaComputation.create("delta", shape(outSize), shape(inSize),
				args -> CollectionExpression.create(getShape(), idx -> args[1].getValueAt(idx)),
				stub, new PackedCollectionMap(getShape(), stub, mapper));
		Producer deltaIn = ((CollectionProducer) getInputs().get(1))
							.delta(target).reshape(shape(inSize, targetSize));
		if (deltaIn instanceof ScopeLifecycle) deltaOut.addDependentLifecycle((ScopeLifecycle) deltaIn);
		return MatrixFeatures.getInstance().mproduct(deltaOut, deltaIn);
	}

	@Override
	public PackedCollectionMap generate(List<Process<?, ?>> children) {
		return new PackedCollectionMap(getShape(), (Producer<PackedCollection>) children.get(1), mapper);
	}

	@Override
	public String signature() { return null; }

	/**
	 * Creates a collection expression that maps a variable reference through sliced access patterns.
	 * Each output index is resolved to the appropriate element within the input collection
	 * by computing the slice and offset based on the provided shapes.
	 *
	 * @param input the collection variable to read values from
	 * @param sliceShape the shape of each individual slice within the input
	 * @return a collection expression implementing sliced element access
	 */
	private CollectionExpression createCollectionExpression(CollectionVariable input, TraversalPolicy sliceShape) {
		return DefaultCollectionExpression.create(sliceShape,
				index -> {
					// Determine which slice to extract
					Expression slice;

					if (sliceShape.getTotalSize() == 1) {
						slice = index;
					} else if (index.getType() == Integer.class) {
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

	/**
	 * Extracts the traversal policy from a collection producer.
	 *
	 * @param collection the collection producer to extract shape from
	 * @return the traversal policy of the collection
	 * @throws IllegalArgumentException if the collection does not implement {@link Shape}
	 */
	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Map cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}

	/**
	 * Internal computation representing a single mapped item during scope preparation.
	 * This computation has a variable count and is never considered constant, ensuring
	 * it is re-evaluated for each invocation of the map operation.
	 */
	private static class ItemComputation extends DefaultTraversableExpressionComputation {
		/**
		 * Creates an item computation with the given shape and expression.
		 *
		 * @param shape the shape of each item produced by this computation
		 * @param expression the expression function used to compute each item
		 * @param args optional input producers for the computation
		 */
		public ItemComputation(TraversalPolicy shape,
							   Function<TraversableExpression[], CollectionExpression> expression,
							   Producer<PackedCollection>... args) {
			super("mapItem", shape, expression, args);
		}

		@Override
		public boolean isFixedCount() {
			return false;
		}

		@Override
		public boolean isConstant() { return false; }
	}
}
