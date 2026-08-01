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

package io.almostrealism.collect;

import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;

import java.util.function.Function;

/**
 * A {@link UniformConditionalExpression} that applies a filter function to each element
 * based on a boolean condition.
 *
 * <p>For each index, the condition is evaluated on the raw element value. When {@code positive}
 * is {@code true}, the filter is applied to elements where the condition holds and the original
 * value is kept where it does not. When {@code positive} is {@code false}, the roles are
 * reversed: the original value is kept where the condition holds and the filter is applied
 * elsewhere.</p>
 */
public class ConditionalFilterExpression extends UniformConditionalExpression {
	/**
	 * Creates a conditional filter expression.
	 *
	 * @param name      a descriptive name for this expression
	 * @param shape     the output shape
	 * @param condition the boolean condition evaluated on each element value
	 * @param filter    the transformation applied to elements that pass (or fail) the condition
	 * @param positive  {@code true} to apply the filter where the condition is true;
	 *                  {@code false} to apply it where the condition is false
	 * @param input     the input operand expression
	 */
	public ConditionalFilterExpression(String name, TraversalPolicy shape,
									   Function<Expression<?>, Expression<Boolean>> condition,
									   Function<Expression<?>, Expression<?>> filter,
									   boolean positive,
									   TraversableExpression input) {
		super(name, shape,
				args -> Conditional.of(condition.apply(args[0]),
						positive ? filter.apply(args[0]) : args[0],
						positive ? args[0] : filter.apply(args[0])),
				input);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Currently delegates to the parent implementation. A future version should
	 * return a {@code ConditionalFilterExpression} that propagates the condition through
	 * the delta of both the filtered and unfiltered branches.</p>
	 */
	@Override
	public CollectionExpression delta(CollectionExpression target) {
		// TODO  This should return a ConditionalFilterExpression
		// TODO  that chooses between the delta of the filtered input
		// TODO  and the delta of the unfiltered input based on
		// TODO  the same condition as is used by this expression
		return super.delta(target);
	}
}
