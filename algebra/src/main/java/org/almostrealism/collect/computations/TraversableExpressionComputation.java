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

import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TraversableExpressionComputation<T extends PackedCollection<?>>
		extends KernelProducerComputationAdapter<T, T>
		implements ComputerFeatures {
	private Function<TraversableExpression[], CollectionExpression> expression;
	private BiFunction<MemoryData, Integer, T> postprocessor;

	private Evaluable<T> shortCircuit;

	@SafeVarargs
	public TraversableExpressionComputation(TraversalPolicy shape,
										BiFunction<TraversableExpression[], Expression, Expression> expression,
										Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		this.expression = vars -> CollectionExpression.create(shape, index -> expression.apply(vars, index));
	}

	@SafeVarargs
	public TraversableExpressionComputation(TraversalPolicy shape,
										Function<TraversableExpression[], CollectionExpression> expression,
										Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, validateArgs(args));
		this.expression = expression;
	}

	public TraversableExpressionComputation<T> setShortCircuit(Evaluable<T> shortCircuit) {
		this.shortCircuit = shortCircuit;
		return this;
	}

	public BiFunction<MemoryData, Integer, T> getPostprocessor() {
		return postprocessor;
	}

	public TraversableExpressionComputation<T> setPostprocessor(BiFunction<MemoryData, Integer, T> postprocessor) {
		this.postprocessor = postprocessor;
		return this;
	}

	protected CollectionExpression getExpression(Expression index) {
		TraversableExpression vars[] = new TraversableExpression[getInputs().size()];
		for (int i = 0; i < vars.length; i++) {
			vars[i] = CollectionExpression.traverse(getArgumentForInput(getInputs().get(i)),
					size -> index.toInt().divide(e(getMemLength())).multiply(size));
		}

		return expression.apply(vars);
	}

	private static Supplier[] validateArgs(Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		Stream.of(args).forEach(Objects::requireNonNull);
		return args;
	}

	@Override
	public Expression<Double> getValue(Expression... pos) { return getValueAt(getShape().index(pos)); }

	@Override
	public Expression getValueAt(Expression index) {
		return getExpression(index).getValueAt(index);
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return getExpression(new IntegerConstant(0)).getValueRelative(index);
	}

	@Override
	public KernelizedEvaluable<T> get() {
		return new KernelizedEvaluable<T>() {
			KernelizedEvaluable<T> kernel;

			private KernelizedEvaluable<T> getKernel() {
				if (kernel == null) {
					kernel = TraversableExpressionComputation.super.get();
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

	public static <T extends PackedCollection<?>> TraversableExpressionComputation<T> fixed(T value) {
		return fixed(value, null);
	}

	public static <T extends PackedCollection<?>> TraversableExpressionComputation<T> fixed(T value, BiFunction<MemoryData, Integer, T> postprocessor) {
		BiFunction<TraversableExpression[], Expression, Expression> comp = (args, index) -> {
			index = index.toInt().mod(new IntegerConstant(value.getShape().getTotalSize()), false);
			index = index.getSimplified();

			OptionalInt i = index.intValue();

			if (i.isPresent()) {
				return value.getValueAt(index);
			} else {
				Expression v = value.getValueAt(new IntegerConstant(0));

				for (int j = 1; j < value.getShape().getTotalSize(); j++) {
					v = new Conditional(index.eq(new IntegerConstant(j)), value.getValueAt(new IntegerConstant(j)), v);
				}

				return v;
			}
		};

		return new TraversableExpressionComputation<T>(value.getShape(), comp).setPostprocessor(postprocessor).setShortCircuit(args -> {
			PackedCollection v = new PackedCollection(value.getShape());
			v.setMem(value.toArray(0, value.getMemLength()));
			return postprocessor == null ? (T) v : postprocessor.apply(v, 0);
		});
	}
}
