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

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class UniformCollectionExpression extends OperandCollectionExpression {
	private Function<Expression[], Expression<?>> operation;
	private NonZeroIndexPolicy indexPolicy;

	public UniformCollectionExpression(String name, TraversalPolicy shape,
									   Function<Expression[], Expression<?>> operation,
									   TraversableExpression... operands) {
		super(name, shape, operands);
		this.operation = operation;

		if (operands.length == 1) {
			this.indexPolicy = NonZeroIndexPolicy.DISJUNCTIVE;
		}
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
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (indexPolicy == null)
			return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);

		switch (indexPolicy) {
			case CONJUNCTIVE:
				// TODO
				throw new UnsupportedOperationException();
			case DISJUNCTIVE:
				return IntStream.range(0, operands.length)
						.mapToObj(i ->
								operands[i].uniqueNonZeroOffset(globalIndex, localIndex, targetIndex))
						.filter(Objects::nonNull)
						.findFirst()
						.orElse(null);
			case EXCLUSIVE:
				Expression offset = null;

				for (int i = 0; i < operands.length; i++) {
					if (operands[i].isIndexIndependent()) {
						Expression v = operands[i].getValueAt(e(0));
						if (v.doubleValue().orElse(-1.0) != 0.0)
							return null;
					} else {
						Expression next = operands[i].uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
						if (next == null)
							return null;

						if (offset == null) {
							offset = next;
						} else if (!Objects.equals(offset, next)) {
							return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
						}
					}
				}

				return offset;
			default:
				return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
		}
	}

	@Override
	public boolean isIndexIndependent() {
		List<TraversableExpression> constants = Stream.of(operands)
				.filter(TraversableExpression::isIndexIndependent)
				.collect(Collectors.toList());

		if (constants.size() >= operands.length) {
			return true;
		} else if (indexPolicy == NonZeroIndexPolicy.DISJUNCTIVE) {
			return constants.stream()
					.map(c -> c.getValueAt(e(0)))
					.anyMatch(v -> v.doubleValue().orElse(-1.0) == 0.0);
		}

		return false;
	}

	public enum NonZeroIndexPolicy {
		CONJUNCTIVE, DISJUNCTIVE, EXCLUSIVE
	}
}
