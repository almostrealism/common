/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.ExpressionList;
import io.almostrealism.code.OperationComputationBase;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.collect.CollectionExpression;
import org.almostrealism.collect.CollectionProducerComputation;
import io.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.KernelSupport;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PackedCollectionMap<T extends PackedCollection<?>>
		extends DynamicCollectionProducerComputationAdapter<PackedCollection<?>, T> {
	private Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper;
	private ExpressionList<Double> result;
	private TraversalPolicy inputShape;

	public PackedCollectionMap(Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		this(shape(collection), collection, mapper);
	}

	public PackedCollectionMap(TraversalPolicy shape, Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		super(shape, (Supplier) collection);
		this.inputShape = shape(collection);
		this.mapper = mapper;

		if (inputShape.getTraversalAxis() != shape.getTraversalAxis()) {
			throw new IllegalArgumentException("Input and output shapes must have the same traversal axis");
		}
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		Expression slice = new StaticReference(Double.class, KernelSupport.getKernelIndex(0)).toDouble();

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

		CollectionVariable inputSlice = input.get(sliceShape, traversalShape.position(slice));
		CollectionExpression expression = CollectionExpression.create(sliceShape, index -> inputSlice.getValueAt(index));

		DynamicCollectionProducerComputationAdapter computation = new DynamicExpressionComputation(sliceShape, args -> expression);

		CollectionProducerComputation<?> mapped = mapper.apply(computation);

		if (mapped instanceof TraversableExpression) {
			ScopeLifecycle.prepareScope(Stream.of(mapped), manager);
			result = IntStream.range(0, getShape().item().getTotalSize())
					.mapToObj(i -> ((TraversableExpression<Double>) mapped).getValueAt(e(i)))
					.collect(ExpressionList.collector());
			return;
		}

		// TODO  This fallback to using ExpressionComputation as input to the mapping function
		// TODO  can eventually be removed when all CollectionProducerComputations are
		// TODO  TraversableExpression implementations.
		ExpressionList<Double> exp = input.get(sliceShape, traversalShape.position(slice)).toList();

		computation = new ExpressionComputation<>(sliceShape,
				IntStream.range(0, exp.size())
						.mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>) args -> exp.get(i))
						.collect(Collectors.toList()));
		computation.setFixedDestinationShape(true);
		CollectionProducerComputation<?> altMapped = mapper.apply(computation);
		ScopeLifecycle.prepareScope(Stream.of(altMapped), manager);

		result = IntStream.range(0, getShape().item().getTotalSize())
					.mapToObj(i -> {
						if (altMapped instanceof PackedCollectionMap) {
							return ((PackedCollectionMap) altMapped).getValue(i);
						}

						Optional<MultiExpression> ex = Optional.empty(); // OperationComputationBase.getExpression(altMapped);
						if (ex.isPresent()) {
							return ex.get().getValue(i);
						} else {
							throw new UnsupportedOperationException();
						}
					})
					.collect(ExpressionList.collector());
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return null;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			if (i >= getMemLength()) throw new IllegalArgumentException("Invalid position");
			return result.get(i);
		};
	}

	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Map cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}
}
