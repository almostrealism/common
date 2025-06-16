/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect.computations;

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.RelativeTraversableExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A specialized {@link ConstantRepeatedProducerComputation} that implements {@link TraversableExpression}
 * for direct value access without kernel compilation. This class enables efficient inline evaluation
 * of repeated computations, particularly useful for small-scale operations or when the computation
 * needs to be embedded within larger expressions.
 * 
 * <p>This class bridges the gap between repeated computations and traversable expressions, allowing
 * repeated operations to be used directly as expressions in larger computation graphs. It's particularly
 * useful for:</p>
 * <ul>
 *   <li>Nested computations where repeated operations are part of larger expressions</li>
 *   <li>Dynamic programming algorithms with recursive subproblems</li>
 *   <li>Inline iterative refinements within complex mathematical expressions</li>
 *   <li>Small-scale repeated operations that don't justify kernel compilation overhead</li>
 * </ul>
 * 
 * <h2>Performance Considerations:</h2>
 * <p>The {@code isolationCountThreshold} controls when this computation should be isolated
 * for independent execution. Higher values favor inline evaluation, while lower values
 * promote kernel compilation for better performance on large datasets.</p>
 * 
 * @param <T> The type of {@link PackedCollection} this computation produces
 * 
 * @see ConstantRepeatedProducerComputation
 * @see TraversableExpression
 */
public class TraversableRepeatedProducerComputation<T extends PackedCollection<?>>
		extends ConstantRepeatedProducerComputation<T> implements TraversableExpression<Double> {
	/**
	 * Threshold for determining when this computation should be isolated for independent execution.
	 * Computations with iteration counts above this threshold are typically isolated to separate
	 * kernels for better performance, while those below are evaluated inline.
	 * 
	 * @see #isIsolationTarget(ProcessContext)
	 */
	public static int isolationCountThreshold = 16; // Integer.MAX_VALUE;

	/**
	 * The traversable expression function that defines the computation for each iteration step.
	 * This function returns a {@link TraversableExpression} rather than a simple {@link Expression},
	 * enabling more complex nested computations and direct value access.
	 */
	private BiFunction<TraversableExpression[], Expression, TraversableExpression<Double>> expression;

	@SafeVarargs
	public TraversableRepeatedProducerComputation(String name, TraversalPolicy shape, int count,
												  BiFunction<TraversableExpression[], Expression, Expression> initial,
												  BiFunction<TraversableExpression[], Expression, TraversableExpression<Double>> expression,
												  Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(name, shape, count, initial, null, arguments);
		this.expression = expression;
		this.count = count;
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		TraversableExpression args[] = getTraversableArguments(index);

		Expression value = initial.apply(args, e(0));

		for (int i = 0; i < count; i++) {
			value = expression.apply(args, value).getValueAt(e(i));
			value = value.generate(value.flatten());
		}

		return value;
	}

	@Override
	protected Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex) {
		Expression currentValue = ((CollectionVariable) ((RelativeTraversableExpression) args[0]).getExpression()).referenceRelative(new IntegerConstant(0));
		return expression.apply(args, currentValue).getValueAt(localIndex);
	}

	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		if (getOutputSize() > MemoryProvider.MAX_RESERVATION) return false;
		return super.isIsolationTarget(context) || count > isolationCountThreshold;
	}

	@Override
	public TraversableRepeatedProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new TraversableRepeatedProducerComputation<>(getName(), getShape(),
				count, initial, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
