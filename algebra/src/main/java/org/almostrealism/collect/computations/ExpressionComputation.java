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

import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.SystemUtils;

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

@Deprecated
public class ExpressionComputation<T extends PackedCollection<?>>
		extends TraversableProducerComputationAdapter<T, T> {
	// SystemUtils.isEnabled("AR_LEGACY").orElse(false) ? false : true;
	public static boolean enableTraversableAdd = true;
	public static boolean enableTraversableMultiply = true;
	public static boolean enableTraversableMin = true;
	public static boolean enableTraversableMax = true;
	public static boolean enableTraversableConditional = true;
	public static boolean enableTraversableComputation = true;

	private List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression;
	private BiFunction<MemoryData, Integer, T> postprocessor;
	private Evaluable<T> shortCircuit;

	@SafeVarargs
	public ExpressionComputation(List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression,
								 Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		// this(shape(expression.size(), args), expression, args);
		this(new TraversalPolicy(expression.size()), expression, args);
	}

	@SafeVarargs
	public ExpressionComputation(TraversalPolicy shape, List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression,
							   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		if (shape.getSize() != expression.size())
			throw new IllegalArgumentException("Expected " + shape.getTotalSize() + " expressions");
		this.expression = expression;
	}

	public ExpressionComputation<T> setShortCircuit(Evaluable<T> shortCircuit) {
		this.shortCircuit = shortCircuit;
		return this;
	}

	public BiFunction<MemoryData, Integer, T> getPostprocessor() {
		return postprocessor;
	}

	public ExpressionComputation<T> setPostprocessor(BiFunction<MemoryData, Integer, T> postprocessor) {
		this.postprocessor = postprocessor;
		return this;
	}

	public List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression() {
		return expression;
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

	private static Supplier[] validateArgs(Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		Stream.of(args).forEach(Objects::requireNonNull);
		return args;
	}

	@Override
	public KernelizedEvaluable<T> get() {
		return new KernelizedEvaluable<T>() {
			KernelizedEvaluable<T> kernel;

			private KernelizedEvaluable<T> getKernel() {
				if (kernel == null) {
					kernel = ExpressionComputation.super.get();
				}

				return kernel;
			}

			@Override
			public MemoryBank<T> createKernelDestination(int size) {
				return getKernel().createKernelDestination(size);
			}

			@Override
			public T evaluate(Object... args) {
				return shortCircuit == null ? getKernel().evaluate(args) : shortCircuit.evaluate(args);
			}

			@Override
			public Evaluable<T> withDestination(MemoryBank<T> destination) {
				return new DestinationEvaluable<>(getKernel(), destination);
			}

			@Override
			public int getArgsCount() {
				return getKernel().getArgsCount();
			}
		};
	}

	@Override
	public T postProcessOutput(MemoryData output, int offset) {
		return getPostprocessor() == null ? super.postProcessOutput(output, offset) : getPostprocessor().apply(output, offset);
	}

	public static <T extends PackedCollection<?>> ExpressionComputation<T> fixed(T value) {
		return fixed(value, null);
	}

	public static <T extends PackedCollection<?>> ExpressionComputation<T> fixed(T value, BiFunction<MemoryData, Integer, T> postprocessor) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, value.getShape().getTotalSize()).forEach(i ->
				comp.add(args -> new DoubleConstant(value.getMem().toArray(value.getOffset() + i, 1)[0])));

		return new ExpressionComputation(comp).setPostprocessor(postprocessor).setShortCircuit(args -> {
			PackedCollection v = new PackedCollection(value.getShape());
			v.setMem(value.toArray(0, value.getMemLength()));
			return postprocessor == null ? v : postprocessor.apply(v, 0);
		});
	}

	private static TraversalPolicy shape(int size, Supplier... args) {
		TraversalPolicy shape = new TraversalPolicy(size);
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
