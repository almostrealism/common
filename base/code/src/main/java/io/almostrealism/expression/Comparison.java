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

package io.almostrealism.expression;

import io.almostrealism.kernel.ArrayIndexSequence;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ScopeSettings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Abstract base class for binary comparison expressions that evaluate to boolean results.
 * This class provides the foundation for implementing comparison operators such as
 * less-than, greater-than, equals, and other relational operations.
 *
 * <p>{@code Comparison} extends {@link BinaryExpression} with Boolean type and provides:</p>
 * <ul>
 *   <li>Abstract {@link #compare(Number, Number)} method for subclasses to implement specific comparisons</li>
 *   <li>Support for compile-time evaluation when operands are constant</li>
 *   <li>Index sequence computation for vectorized operations</li>
 *   <li>Expression simplification with constant folding and index option analysis</li>
 * </ul>
 *
 * <p>Subclasses must implement the {@link #compare(Number, Number)} method to define
 * the specific comparison semantics (e.g., less-than, greater-than, equals).</p>
 *
 * @see BinaryExpression
 * @see Conditional
 */
public abstract class Comparison extends BinaryExpression<Boolean> {

	/**
	 * Global flag to enable or disable index option simplification during
	 * expression simplification. When enabled, comparisons with limited
	 * discrete index values may be simplified based on enumeration of outcomes.
	 */
	public static boolean enableIndexOptionSimplification = true;

	/**
	 * Maximum number of index options to consider during simplification.
	 * Comparisons with more possible index values than this limit will
	 * not be simplified via enumeration.
	 */
	public static int indexOptionLimit = ScopeSettings.indexOptionLimit >> 1;

	/**
	 * Constructs a new comparison expression with the given left and right operands.
	 *
	 * @param left  the left operand of the comparison
	 * @param right the right operand of the comparison
	 */
	public Comparison(Expression<?> left, Expression<?> right) {
		super(Boolean.class, left, right);
	}

	/**
	 * Performs the specific comparison operation on two numeric values.
	 * Subclasses must implement this method to define the comparison semantics.
	 *
	 * @param left  the left operand value
	 * @param right the right operand value
	 * @return {@code true} if the comparison holds, {@code false} otherwise
	 */
	protected abstract boolean compare(Number left, Number right);

	/**
	 * Determines whether this comparison can be evaluated to a concrete value
	 * given the specified index values. Returns true if both operands can be
	 * evaluated with the given index values.
	 *
	 * @param values the index values to check against
	 * @return {@code true} if both operands can be evaluated, {@code false} otherwise
	 */
	@Override
	public boolean isValue(IndexValues values) {
		return getLeft().isValue(values) && getRight().isValue(values);
	}

	/**
	 * Evaluates this comparison for the given index values and returns the result
	 * as a numeric value (1 for true, 0 for false).
	 *
	 * @param indexValues the index values to use for evaluation
	 * @return 1 if the comparison is true, 0 if false
	 */
	@Override
	public Number value(IndexValues indexValues) {
		return compare(getLeft().value(indexValues), getRight().value(indexValues)) ? 1 : 0;
	}

	/**
	 * Computes an index sequence representing the comparison results for a range
	 * of index values. This enables vectorized evaluation of comparisons across
	 * multiple indices.
	 *
	 * @param index the index variable to sequence over
	 * @param len   the length of the sequence to generate
	 * @param limit the maximum sequence length to consider
	 * @return an {@link IndexSequence} of comparison results (1 or 0 values),
	 *         or {@code null} if sequencing is not possible
	 */
	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		IndexValues values = IndexValues.of(index);
		if (!getLeft().isValue(values) || !getRight().isValue(values)) {
			return super.sequence(index, len, limit);
		}

		if (index instanceof KernelIndex) {
			int seq[] = checkSingle(getLeft(), getRight(), Math.toIntExact(len));
			if (seq != null) return ArrayIndexSequence.of(seq);

			seq = checkSingle(getRight(), getLeft(), Math.toIntExact(len));
			if (seq != null) return ArrayIndexSequence.of(seq);
		}

		IndexSequence l = getLeft().sequence(index, len, limit);
		if (l == null) return null;

		IndexSequence r = getRight().sequence(index, len, limit);
		if (r == null) return null;

		return compare(l, r, len);
	}

	/**
	 * Compares two index sequences element-wise and returns a sequence of results.
	 *
	 * @param left  the left operand sequence
	 * @param right the right operand sequence
	 * @param len   the length of sequences to compare
	 * @return an {@link IndexSequence} with 1 for true comparisons, 0 for false,
	 *         or {@code null} if the length exceeds Integer.MAX_VALUE
	 */
	protected IndexSequence compare(IndexSequence left, IndexSequence right, long len) {
		if (len > Integer.MAX_VALUE) return null;

		return ArrayIndexSequence.of(Integer.class, IntStream.range(0, Math.toIntExact(len))
				.mapToObj(i -> compare(left.valueAt(i), right.valueAt(i)) ? Integer.valueOf(1) : Integer.valueOf(0))
				.toArray(Number[]::new));
	}

	/**
	 * Checks for a single-value optimization pattern. Subclasses may override
	 * this to provide optimized sequence generation for specific patterns.
	 *
	 * @param left  the left operand expression
	 * @param right the right operand expression
	 * @param len   the sequence length
	 * @return an optimized int array if the pattern matches, or {@code null}
	 */
	protected int[] checkSingle(Expression left, Expression right, int len) {
		return null;
	}

	/**
	 * Evaluates the comparison on the given child operand values.
	 *
	 * @param children the operand values (expects exactly 2 values)
	 * @return 1 if the comparison is true, 0 if false
	 */
	@Override
	public Number evaluate(Number... children) {
		return compare(children[0], children[1]) ? 1 : 0;
	}

	/**
	 * Simplifies this comparison expression using various optimization strategies:
	 * <ul>
	 *   <li>Constant folding when both operands are compile-time constants</li>
	 *   <li>Index option enumeration for expressions with limited discrete values</li>
	 *   <li>Series provider delegation for suitable simplification targets</li>
	 * </ul>
	 *
	 * @param context the kernel structure context providing simplification resources
	 * @param depth   the current simplification depth
	 * @return a simplified expression, potentially a {@link BooleanConstant} if
	 *         fully evaluable at compile time
	 */
	@Override
	public Expression<Boolean> simplify(KernelStructureContext context, int depth) {
		Expression<Boolean> flat = super.simplify(context, depth);
		if (!Objects.equals(flat.getClass(), getClass())) return flat;

		Expression<?> left = flat.getChildren().get(0);
		Expression<?> right = flat.getChildren().get(1);

		OptionalInt li = left.intValue();
		OptionalInt ri = right.intValue();
		if (li.isPresent() && ri.isPresent())
			return new BooleanConstant(compare(li.getAsInt(), ri.getAsInt()));

		OptionalDouble ld = left.doubleValue();
		OptionalDouble rd = right.doubleValue();
		if (ld.isPresent() && rd.isPresent())
			return new BooleanConstant(compare(ld.getAsDouble(), rd.getAsDouble()));

		Optional<Set<Integer>> options = enableIndexOptionSimplification ?
				flat.getIndexOptions(kernel(context)) : Optional.empty();
		if (options.isPresent() && flat.countNodes() > 3) {
			if (options.get().isEmpty() || options.get().size() > indexOptionLimit ||
					!flat.isValue(new IndexValues(0))) {
				return flat;
			}

			List<Integer> inputs = options.get().stream().sorted().collect(Collectors.toList());
			List<Boolean> values = inputs.stream()
					.map(i -> flat.value(new IndexValues(i)).longValue() != 0)
					.collect(Collectors.toList());

			List<Boolean> distinct = values.stream().distinct().collect(Collectors.toList());
			if (distinct.size() == 1) {
				return new BooleanConstant(distinct.get(0));
			}
		}

		if (flat.isSeriesSimplificationTarget(depth) && context.getSeriesProvider() != null) {
			return context.getSeriesProvider().getSeries(flat);
		}

		return flat;
	}
}
