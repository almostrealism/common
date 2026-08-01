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

import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A collection expression that combines groups of element values from one or more operands
 * using a {@code combiner} function.
 *
 * <p>For each output index, a fixed number of {@code memberCount} elements are gathered from
 * each operand according to the {@link MemberIndexGenerator}. The gathered members for all
 * operands are passed as a list of arrays to the {@code combiner} function, which produces
 * the final output value.</p>
 *
 * <p>This base class is extended by {@link BinaryGroupExpression} for the common two-operand
 * case (inputs and weights), and by {@link WeightedSumExpression} for the specific
 * dot-product combination.</p>
 */
public class GroupExpression extends OperandCollectionExpression {
	/** The number of elements gathered from each operand per output index. */
	private final int memberCount;

	/** Generates the index expression used to look up each group member in each operand. */
	private final MemberIndexGenerator memberGenerator;

	/** Combines the gathered member arrays across all operands into a single output value. */
	private final Function<List<Expression[]>, Expression> combiner;

	/**
	 * Creates a group expression using a list of index expressions (one per member).
	 *
	 * @param name          a descriptive name for this expression
	 * @param shape         the output shape
	 * @param memberIndices the index expressions for each group member (same for all operands)
	 * @param combiner      the function that combines members from all operands into a value
	 * @param operands      the input operand expressions
	 */
	public GroupExpression(String name, TraversalPolicy shape,
						   List<TraversableExpression<?>> memberIndices,
						   Function<List<Expression[]>, Expression> combiner,
						   TraversableExpression... operands) {
		this(name, shape, memberIndices.size(), memberIndices::get, combiner, operands);
	}

	/**
	 * Creates a group expression using a single index generator shared across all operands.
	 *
	 * @param name                 a descriptive name for this expression
	 * @param shape                the output shape
	 * @param memberCount          the number of group members per output index
	 * @param memberIndexGenerator a function from member index to the index expression to use
	 * @param combiner             the function that combines members from all operands into a value
	 * @param operands             the input operand expressions
	 */
	public GroupExpression(String name, TraversalPolicy shape,
						   int memberCount,
						   IntFunction<TraversableExpression<?>> memberIndexGenerator,
						   Function<List<Expression[]>, Expression> combiner,
						   TraversableExpression... operands) {
		this(name, shape, memberCount, (memberIndex, operandIndex) ->
						memberIndexGenerator.apply(memberIndex),
				combiner, operands);
	}

	/**
	 * Creates a group expression with a full {@link MemberIndexGenerator} that can vary
	 * the index expression per (member, operand) pair.
	 *
	 * @param name                 a descriptive name for this expression
	 * @param shape                the output shape
	 * @param memberCount          the number of group members per output index
	 * @param memberIndexGenerator the generator producing the index expression for each member/operand
	 * @param combiner             the function that combines members from all operands into a value
	 * @param operands             the input operand expressions
	 */
	public GroupExpression(String name, TraversalPolicy shape,
						   int memberCount,
						   MemberIndexGenerator memberIndexGenerator,
						   Function<List<Expression[]>, Expression> combiner,
						   TraversableExpression... operands) {
		super(name, shape, operands);
		this.memberCount = memberCount;
		this.memberGenerator = memberIndexGenerator;
		this.combiner = combiner;
	}

	/**
	 * Gathers the group member values from all operands for the given output index.
	 *
	 * @param index the output index at which members are gathered
	 * @return a list of arrays, one array per operand, each containing the gathered member values
	 */
	protected List<Expression[]> getMembers(Expression<?> index) {
		return IntStream.range(0, getOperands().size())
				.mapToObj(operand -> {
					Expression opMembers[] = new Expression[memberCount];

					for (int memberIndex = 0; memberIndex < memberCount; memberIndex++) {
						opMembers[memberIndex] = getOperands().get(operand).getValueAt(
								memberGenerator.indexGenerator(memberIndex, operand).getValueAt(index));
					}

					return opMembers;
				}).collect(Collectors.toUnmodifiableList());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Gathers the group members and passes them to the combiner function.</p>
	 */
	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		List<Expression[]> members = getMembers(index);
		return combiner.apply(members);
	}

	/**
	 * Generates the index expression used to look up a specific group member in a specific operand.
	 */
	public interface MemberIndexGenerator {
		/**
		 * Returns the index expression for the given member and operand indices.
		 *
		 * @param memberIndex  the zero-based group member position
		 * @param operandIndex the zero-based operand position
		 * @return the traversable expression used to compute the input index for this member/operand
		 */
		TraversableExpression<?> indexGenerator(int memberIndex, int operandIndex);
	}
}
