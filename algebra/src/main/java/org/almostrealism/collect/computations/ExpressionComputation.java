/*
 * Copyright 2022 Michael Murray
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
import io.almostrealism.expression.MultiExpression;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ExpressionComputation<T extends PackedCollection<?>> extends DynamicCollectionProducerComputationAdapter<T, T> implements ComputerFeatures {
	private List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression;

	private Evaluable<T> shortCircuit;

	@SafeVarargs
	public ExpressionComputation(List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression,
								 Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(new TraversalPolicy(expression.size()), expression, args);
	}

	@SafeVarargs
	public ExpressionComputation(TraversalPolicy shape, List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression,
							   Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		if (shape.getTotalSize() != expression.size())
			throw new IllegalArgumentException("Expected " + shape.getTotalSize() + " expressions");
		this.expression = expression;
	}

	public void setShortCircuit(Evaluable<T> shortCircuit) {
		this.shortCircuit = shortCircuit;
	}

	public List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression() {
		return expression;
	}

	public List<MultiExpression<Double>> getExpressions() {
		return IntStream.range(0, getInputs().size())
				.mapToObj(i -> (MultiExpression<Double>) pos -> getInputValue(i, pos))
				.collect(Collectors.toList());
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (pos >= expression.size()) {
				throw new IllegalArgumentException();
			} else {
				return expression.get(pos).apply(getExpressions());
			}
		};
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
			public void kernelEvaluate(MemoryBank destination, MemoryData... args) {
				getKernel().kernelEvaluate(destination, args);
			}

			@Override
			public int getArgsCount() {
				return getKernel().getArgsCount();
			}
		};
	}
}
