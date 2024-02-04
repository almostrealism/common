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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ExpressionComputation<T extends PackedCollection<?>>
		extends RelativeTraversableProducerComputation<T, T> {

	public static boolean enableTraversableFixed = false;
	public static boolean enableInferShape = false;
	public static boolean enableWarnings = false;

	private List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression;

	@SafeVarargs
	public ExpressionComputation(List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression,
								 Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(shape(expression.size(), args), expression, args);
	}

	@SafeVarargs
	public ExpressionComputation(TraversalPolicy shape, List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression,
							   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		if (shape.getTotalSize() != expression.size())
			throw new IllegalArgumentException("Expected " + shape.getTotalSize() + " expressions");
		this.expression = expression;

		if (enableWarnings && expression instanceof ArrayList) {
			warn("Modifiable list used as argument to ExpressionComputation constructor");
		}
	}

	public List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression() {
		return expression;
	}

	@Override
	protected MemoryBank<?> adjustDestination(MemoryBank<?> existing, Integer len) {
		if (len == null) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy shape = shapeForLength(len);

		if (!(existing instanceof PackedCollection) || existing.getMem() == null ||
				((PackedCollection) existing).getShape().getTotalSize() < shape.getTotalSize()) {
			if (existing != null) existing.destroy();
			return PackedCollection.factory().apply(shape.getTotalSize()).reshape(shape);
		}

		return ((PackedCollection) existing).range(shape);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (pos >= expression.size()) {
				throw new IllegalArgumentException();
			} else {
				return expression.get(pos).apply(getInputArguments());
			}
		};
	}

	public Expression<Double> getValue(List<ArrayVariable<Double>> args, int index) {
		return expression.get(index).apply(args);
	}

	@Override
	public ExpressionComputation<T> generate(List<Process<?, ?>> children) {
		return new ExpressionComputation<>(getShape(), expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}

	public static <T extends PackedCollection<?>> ExpressionComputation<T> fixed(T value) {
		return fixed(value, null);
	}

	public static <T extends PackedCollection<?>> ExpressionComputation<T> fixed(T value, BiFunction<MemoryData, Integer, T> postprocessor) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> comp[] =
			IntStream.range(0, value.getShape().getTotalSize())
					.mapToObj(i ->
						(Function<List<ArrayVariable<Double>>, Expression<Double>>) args -> value.getValueAt(new IntegerConstant(i)))
					.toArray(Function[]::new);

		return (ExpressionComputation<T>) new ExpressionComputation(value.getShape(), List.of(comp)).setPostprocessor(postprocessor).setShortCircuit(args -> {
			PackedCollection v = new PackedCollection(value.getShape());
			v.setMem(value.toArray(0, value.getMemLength()));
			return postprocessor == null ? v : postprocessor.apply(v, 0);
		});
	}

	private static TraversalPolicy shape(int size, Supplier... args) {
		TraversalPolicy shape = new TraversalPolicy(size);
		if (!enableInferShape) return shape;

		Set<Integer> count = Stream.of(args)
				.map(CollectionFeatures.getInstance()::shape)
				.map(TraversalPolicy::getCount)
				.filter(i -> i > 1)
				.collect(Collectors.toSet());
		if (count.isEmpty()) {
			return shape;
		} else if (count.size() == 1) {
			return shape.prependDimension(count.iterator().next());
		} else {
			throw new IllegalArgumentException("Unable to infer shape from arguments");
		}
	}
}
