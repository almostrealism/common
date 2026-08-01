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
import io.almostrealism.sequence.Index;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A collection expression that applies a single element-wise operation to all operands
 * at every index.
 *
 * <p>For each output index, the same index is passed to every operand and the resulting
 * values are combined by the {@code operation} function. Subclasses specialise this by
 * supplying a fixed operation (e.g., {@link ProductCollectionExpression} uses multiplication).</p>
 *
 * <p>The {@link NonZeroIndexPolicy} controls how the unique non-zero offset is computed from
 * the operand non-zero offsets, enabling the code generator to elide memory accesses for
 * zero-valued elements.</p>
 */
public class UniformCollectionExpression extends OperandCollectionExpression {
	/** The element-wise operation applied to the operand values at each index. */
	private Function<Expression[], Expression<?>> operation;

	/** The policy used to combine per-operand non-zero offsets, or {@code null} for the default. */
	private NonZeroIndexPolicy indexPolicy;

	/**
	 * Creates a uniform collection expression with the given name, shape, operation, and operands.
	 *
	 * <p>When there is exactly one operand the index policy defaults to
	 * {@link NonZeroIndexPolicy#DISJUNCTIVE}.</p>
	 *
	 * @param name      a descriptive name for this expression
	 * @param shape     the output shape
	 * @param operation the element-wise operation applied at each index
	 * @param operands  the input operand expressions
	 */
	public UniformCollectionExpression(String name, TraversalPolicy shape,
									   Function<Expression[], Expression<?>> operation,
									   TraversableExpression... operands) {
		super(name, shape, operands);
		this.operation = operation;

		if (operands.length == 1) {
			this.indexPolicy = NonZeroIndexPolicy.DISJUNCTIVE;
		}
	}

	/**
	 * Returns the non-zero index policy used when computing the unique non-zero offset.
	 *
	 * @return the current policy, or {@code null} if none is set
	 */
	public NonZeroIndexPolicy getIndexPolicy() {
		return indexPolicy;
	}

	/**
	 * Sets the non-zero index policy.
	 *
	 * @param indexPolicy the policy to apply
	 */
	public void setIndexPolicy(NonZeroIndexPolicy indexPolicy) {
		this.indexPolicy = indexPolicy;
	}

	/** {@inheritDoc} Returns {@code operation.apply(operand values at index)}. */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		Expression args[] = new Expression[operands.length];
		IntStream.range(0, operands.length).forEach(i -> args[i] = operands[i].getValueAt(index));
		return (Expression<Double>) operation.apply(args);
	}

	/** {@inheritDoc} Applies the {@link NonZeroIndexPolicy} to compute the unique non-zero offset. */
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

	/** {@inheritDoc} */
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

	/**
	 * Controls how per-operand non-zero offsets are combined when computing
	 * the unique non-zero offset for this expression.
	 */
	public enum NonZeroIndexPolicy {
		/**
		 * All operands must have the same non-zero offset (not yet implemented).
		 */
		CONJUNCTIVE,

		/**
		 * The first operand with a non-null non-zero offset is used.
		 * Appropriate when at most one operand is non-zero for any given index.
		 */
		DISJUNCTIVE,

		/**
		 * Exactly one non-index-independent operand may be non-zero; index-independent
		 * operands must evaluate to zero. If multiple non-zero offsets disagree, falls back
		 * to the default implementation.
		 */
		EXCLUSIVE
	}
}
