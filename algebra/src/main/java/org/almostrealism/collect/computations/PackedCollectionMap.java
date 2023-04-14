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
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.MemoryBank;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PackedCollectionMap<T extends PackedCollection<?>>
		extends DynamicCollectionProducerComputationAdapter<PackedCollection<?>, T>
		implements CollectionProducerComputation<T> {
	private Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper;
	private ExpressionList<Double> result;

	public PackedCollectionMap(Producer<?> collection, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		super(shape(collection), (Supplier) collection);
		this.mapper = mapper;
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		Expression slice = new Expression(Double.class, KernelSupport.getKernelIndex(0));
		CollectionVariable input = (CollectionVariable) getArgument(1, getShape().getTotalSize());

		TraversalPolicy sliceShape = getShape().item();
		while (sliceShape.getDimensions() < getShape().getDimensions())
			sliceShape = sliceShape.prependDimension(1);

		ExpressionList<Double> exp = input.get(sliceShape, slice).toList();

		ExpressionComputation<?> computation = new ExpressionComputation<>(getShape().item(),
				IntStream.range(0, exp.size())
						.mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>) args -> exp.get(i))
						.collect(Collectors.toList()));

		CollectionProducerComputation<?> mapped = mapper.apply(computation);
		ScopeLifecycle.prepareScope(Stream.of(mapped), manager);

		result = IntStream.range(0, getShape().item().getTotalSize())
				.mapToObj(i -> OperationComputationBase.getExpression(mapped).orElseThrow().getValue(i))
				.collect(ExpressionList.collector());
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
