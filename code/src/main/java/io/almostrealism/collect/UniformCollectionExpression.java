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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;

import java.util.Objects;
import java.util.function.Function;
import java.util.stream.IntStream;

public class UniformCollectionExpression extends CollectionExpressionAdapter {
	private Function<Expression[], Expression<?>> operation;
	private TraversableExpression[] operands;
	private NonZeroIndexPolicy indexPolicy;

	public UniformCollectionExpression(TraversalPolicy shape,
									   Function<Expression[], Expression<?>> operation,
									   TraversableExpression... operands) {
		super(shape);
		this.operation = operation;
		this.operands = operands;
	}

	public NonZeroIndexPolicy getIndexPolicy() {
		return indexPolicy;
	}

	public void setIndexPolicy(NonZeroIndexPolicy indexPolicy) {
		this.indexPolicy = indexPolicy;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		Expression args[] = new Expression[operands.length];
		IntStream.range(0, operands.length).forEach(i -> args[i] = operands[i].getValueAt(index));
		return (Expression<Double>) operation.apply(args);
	}

	@Override
	public Expression uniqueNonZeroIndex(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (indexPolicy == null)
			return super.uniqueNonZeroIndex(globalIndex, localIndex, targetIndex);

		switch (indexPolicy) {
			case CONJUNCTIVE:
				// TODO
				throw new UnsupportedOperationException();
			case DISJUNCTIVE:
				return IntStream.range(0, operands.length)
						.mapToObj(i ->
								operands[i].uniqueNonZeroIndex(globalIndex, localIndex, targetIndex))
						.filter(Objects::nonNull)
						.findFirst()
						.orElse(null);
			case EXCLUSIVE:
				// TODO
				throw new UnsupportedOperationException();
			default:
				return super.uniqueNonZeroIndex(globalIndex, localIndex, targetIndex);
		}
	}

	public enum NonZeroIndexPolicy {
		CONJUNCTIVE, DISJUNCTIVE, EXCLUSIVE
	}
}
