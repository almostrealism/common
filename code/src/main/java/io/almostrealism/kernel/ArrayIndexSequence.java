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

package io.almostrealism.kernel;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.util.ArrayItem;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.LongUnaryOperator;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * An {@link IndexSequence} implementation that stores index values explicitly in an array.
 *
 * <p>{@code ArrayIndexSequence} is a general-purpose implementation of {@link IndexSequence}
 * that stores sequence values in an underlying array (via {@link ArrayItem}). It supports
 * arbitrary sequences of numeric values and provides transformation operations that produce
 * new sequences.
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>Explicit Storage</b>: Values are stored in an array, suitable for arbitrary sequences
 *       that don't follow a simple pattern</li>
 *   <li><b>Granularity Support</b>: Supports sequences where multiple consecutive indices
 *       map to the same value, controlled by the {@link #granularity} field</li>
 *   <li><b>Lazy Min/Max Computation</b>: Minimum and maximum values are computed on first
 *       access and cached for subsequent calls</li>
 *   <li><b>Base64 Signatures</b>: Provides compact signatures for sequence comparison and caching</li>
 * </ul>
 *
 * <h2>Comparison with ArithmeticIndexSequence</h2>
 * <p>While {@link ArithmeticIndexSequence} efficiently represents sequences that follow
 * a mathematical pattern ({@code offset + scale * (pos / granularity)}), this class stores
 * values explicitly. Use {@code ArrayIndexSequence} when:
 * <ul>
 *   <li>The sequence doesn't follow an arithmetic pattern</li>
 *   <li>Values are computed from complex expressions</li>
 *   <li>The sequence is the result of arbitrary transformations</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Create from an int array
 * IndexSequence seq = ArrayIndexSequence.of(new int[]{0, 1, 2, 3, 4});
 *
 * // Create from Number array with explicit type
 * IndexSequence seq = ArrayIndexSequence.of(Integer.class, new Number[]{0, 2, 4, 6});
 *
 * // Create a constant sequence (single value repeated)
 * IndexSequence constant = ArrayIndexSequence.of(42, 1000);  // 1000 copies of 42
 *
 * // Create from an expression and index values
 * IndexSequence seq = ArrayIndexSequence.of(expression, indexValues, length);
 *
 * // Transform sequences
 * IndexSequence doubled = seq.mapInt(i -> i * 2);
 * IndexSequence offset = seq.mapLong(l -> l + 100);
 * }</pre>
 *
 * <h2>Granularity</h2>
 * <p>The granularity determines how many consecutive index positions map to the same
 * stored value. For a sequence with granularity 3 and values [0, 1, 2]:
 * <ul>
 *   <li>Positions 0, 1, 2 all return 0</li>
 *   <li>Positions 3, 4, 5 all return 1</li>
 *   <li>Positions 6, 7, 8 all return 2</li>
 * </ul>
 *
 * <h2>Expression Generation</h2>
 * <p>The {@link #getExpression(Index)} method attempts to detect patterns in the sequence
 * and generate optimized expressions. If the sequence is constant, it returns an
 * {@link IntegerConstant}. If the sequence values match the index positions exactly
 * (an identity sequence), it returns the index expression directly.
 *
 * @author Michael Murray
 * @see IndexSequence
 * @see ArithmeticIndexSequence
 * @see ArrayItem
 * @see SequenceGenerator
 */
public class ArrayIndexSequence extends ArrayItem<Number> implements IndexSequence {
	/**
	 * Base64 encoder used for generating compact sequence signatures.
	 * Used by {@link #signature()} to create unique identifiers for caching and comparison.
	 */
	private final Base64.Encoder encoder = Base64.getEncoder();

	/**
	 * Cached minimum value in the sequence.
	 * Computed lazily on first call to {@link #min()} and cached for subsequent calls.
	 * A {@code null} value indicates the minimum has not yet been computed.
	 */
	private Long min;

	/**
	 * Cached maximum value in the sequence.
	 * Computed lazily on first call to {@link #max()} and cached for subsequent calls.
	 * A {@code null} value indicates the maximum has not yet been computed.
	 */
	private Long max;

	/**
	 * The number of consecutive index positions that map to the same stored value.
	 *
	 * <p>When accessing values via {@link #valueAt(long)}, the position is divided by
	 * this granularity value before looking up the stored value. This allows efficient
	 * representation of sequences where multiple consecutive indices share a value.
	 *
	 * <p>For example, with granularity 4 and stored values [10, 20, 30]:
	 * <ul>
	 *   <li>Positions 0-3 return 10</li>
	 *   <li>Positions 4-7 return 20</li>
	 *   <li>Positions 8-11 return 30</li>
	 * </ul>
	 *
	 * <p>A granularity of 1 means each position maps to a unique stored value (no repetition).
	 */
	private int granularity;

	/**
	 * Constructs an ArrayIndexSequence with explicit values and granularity.
	 *
	 * <p>This constructor creates a sequence that stores the provided values and
	 * uses the specified granularity to map index positions to stored values.
	 * The actual sequence length may exceed the array length when granularity
	 * is greater than 1.
	 *
	 * @param type the numeric type of the values (e.g., {@code Integer.class}, {@code Double.class}),
	 *             or {@code null} to infer from values
	 * @param values the array of values to store; must not be empty
	 * @param granularity the number of consecutive index positions that map to the same value;
	 *                    must be positive
	 * @param len the logical length of the sequence; positions 0 to len-1 are valid
	 * @throws IllegalArgumentException if values is empty or len is less than 1
	 */
	protected ArrayIndexSequence(Class<Number> type, Number[] values, int granularity, long len) {
		super(type, values, len, Number[]::new);
		this.granularity = granularity;
	}

	/**
	 * Constructs a constant ArrayIndexSequence where all positions return the same value.
	 *
	 * <p>This constructor creates an efficient constant sequence where every index
	 * position returns the same value. Internally, only a single value is stored,
	 * making this memory-efficient for large constant sequences.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * // Create a sequence of 1000 elements, all with value 42
	 * ArrayIndexSequence constant = new ArrayIndexSequence(42, 1000);
	 * // constant.valueAt(0) == constant.valueAt(999) == 42
	 * }</pre>
	 *
	 * @param value the constant value to return for all index positions
	 * @param len the logical length of the sequence; positions 0 to len-1 are valid
	 * @throws IllegalArgumentException if len is less than 1
	 */
	protected ArrayIndexSequence(Number value, long len) {
		super(value, len, Number[]::new);
		this.granularity = 1;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the value at the specified position, accounting for granularity.
	 * The position is divided by the granularity before looking up the stored value,
	 * meaning multiple consecutive positions will return the same value when
	 * granularity is greater than 1.
	 *
	 * @param pos the index position (0-based)
	 * @return the numeric value at the specified position
	 */
	@Override
	public Number valueAt(long pos) {
		return super.valueAt(pos / granularity);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation applies the operator to the stored values and creates
	 * a new {@code ArrayIndexSequence} with the same length. The result type is
	 * preserved from the original sequence.
	 *
	 * @param op the transformation operator to apply to each value
	 * @return a new {@code IndexSequence} with the transformed values
	 */
	public IndexSequence map(UnaryOperator<Number> op) {
		return ArrayIndexSequence.of(type, apply(op, Number[]::new), lengthLong());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation converts each value to an integer, applies the operator,
	 * and creates a new {@code ArrayIndexSequence} with {@code Integer} as the type.
	 *
	 * @param op the integer transformation operator to apply
	 * @return a new {@code IndexSequence} with integer-typed transformed values
	 */
	public IndexSequence mapInt(IntUnaryOperator op) {
		return ArrayIndexSequence.of(Integer.class, apply(v -> op.applyAsInt(v.intValue()), Number[]::new), lengthLong());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation converts each value to a long, applies the operator,
	 * and creates a new {@code ArrayIndexSequence} with {@code Long} as the type.
	 *
	 * @param op the long transformation operator to apply
	 * @return a new {@code IndexSequence} with long-typed transformed values
	 */
	public IndexSequence mapLong(LongUnaryOperator op) {
		return ArrayIndexSequence.of(Long.class, apply(v -> op.applyAsLong(v.longValue()), Number[]::new), lengthLong());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>This implementation converts each value to a double, applies the operator,
	 * and creates a new {@code ArrayIndexSequence} with {@code Double} as the type.
	 *
	 * @param op the double transformation operator to apply
	 * @return a new {@code IndexSequence} with double-typed transformed values
	 */
	public IndexSequence mapDouble(DoubleUnaryOperator op) {
		return ArrayIndexSequence.of(Double.class, apply(v -> op.applyAsDouble(v.doubleValue()), Number[]::new), lengthLong());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a subset of this sequence containing only the first {@code len} elements.
	 * If the requested length equals the current length, this sequence is returned unchanged.
	 * The resulting sequence has granularity 1 (no value repetition).
	 *
	 * @param len the number of elements in the subset
	 * @return this sequence if {@code len} equals the current length,
	 *         otherwise a new {@code ArrayIndexSequence} with the first {@code len} elements
	 */
	@Override
	public IndexSequence subset(long len) {
		if (len == lengthLong())
			return this;

		return new ArrayIndexSequence(type,
				Arrays.copyOf(toArray(), Math.toIntExact(len)),
				1, Math.toIntExact(len));
	}


	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a stream of all index positions where the sequence value equals
	 * the specified value. This implementation checks every position in the sequence.
	 *
	 * @param value the value to search for
	 * @return a {@code LongStream} of index positions where the value matches
	 */
	@Override
	public LongStream matchingIndices(double value) {
		return LongStream.range(0, lengthLong()).filter(i -> valueAt(i).doubleValue() == value);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the minimum value in this sequence. The result is computed on
	 * first call and cached for subsequent calls.
	 *
	 * @return the minimum value as a {@code long}
	 */
	@Override
	public long min() {
		if (min == null) {
			min = IndexSequence.super.min();
		}

		return min;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the maximum value in this sequence. The result is computed on
	 * first call and cached for subsequent calls.
	 *
	 * @return the maximum value as a {@code long}
	 */
	@Override
	public long max() {
		if (max == null) {
			max = IndexSequence.super.max();
		}

		return max;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>A sequence is constant if it contains only a single unique value
	 * (i.e., the {@code single} field from {@link ArrayItem} is non-null).
	 *
	 * @return {@code true} if all positions in this sequence return the same value
	 */
	public boolean isConstant() { return single() != null; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the granularity of this sequence, which indicates how many consecutive
	 * index positions share the same value. This method first checks the stored granularity;
	 * if it is greater than 1, that value is returned. Otherwise, it attempts to detect
	 * the granularity by examining the sequence values.
	 *
	 * <p>The detection algorithm:
	 * <ol>
	 *   <li>If the sequence is constant, returns the full length (all positions are identical)</li>
	 *   <li>Finds the first position where the value changes from the initial value</li>
	 *   <li>Verifies that this pattern holds throughout the sequence</li>
	 *   <li>Returns 1 if no consistent granularity pattern is found</li>
	 * </ol>
	 *
	 * @return the number of consecutive positions that share the same value, or 1 if
	 *         values change at every position or no consistent pattern is detected
	 */
	public int getGranularity() {
		if (granularity > 1) return granularity;
		if (lengthLong() > Integer.MAX_VALUE) return 1;
		if (single() != null) return length();

		int granularity = 1;

		i: for (int i = 0; i < length() - 1; i++) {
			if (!Objects.equals(valueAt(i), valueAt(i + 1))) {
				granularity = i + 1;
				break i;
			}
		}

		if (granularity == 1) return granularity;

		long sections = lengthLong() / granularity;

		for (int i = 0; i < sections; i++) {
			for (int j = 1; j < granularity & i * granularity + j < length(); j++) {
				if (!Objects.equals(valueAt(i * granularity), valueAt(i * granularity + j))) {
					return 1;
				}
			}
		}

		return granularity;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the modulus (cycle length) of this sequence. The modulus accounts for
	 * both the stored array's modulus from {@link ArrayItem#getMod()} and the granularity.
	 * The effective modulus is {@code granularity * arrayMod}.
	 *
	 * @return the cycle length after which the sequence repeats
	 * @throws UnsupportedOperationException if the computed modulus exceeds {@code Integer.MAX_VALUE}
	 */
	@Override
	public long getMod() {
		long g = granularity;
		g = g * super.getMod();

		if (g > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}

		return Math.toIntExact(g);
	}


	/**
	 * {@inheritDoc}
	 *
	 * <p>Generates an {@link Expression} that computes the values of this sequence.
	 * This implementation provides optimizations for common patterns:
	 * <ul>
	 *   <li><b>Constant sequences</b>: Returns an {@link IntegerConstant} with the single value</li>
	 *   <li><b>Identity sequences</b>: If the sequence values equal their index positions
	 *       (i.e., {@code valueAt(i) == i} for all i), returns the index expression directly</li>
	 *   <li><b>Other sequences</b>: Delegates to {@link IndexSequence#getExpression(Expression, boolean)}
	 *       for pattern detection and expression generation</li>
	 * </ul>
	 *
	 * @param index the index expression to use as the input variable
	 * @return an expression that computes the sequence value for any given index
	 */
	@Override
	public Expression<? extends Number> getExpression(Index index) {
		if (isConstant()) {
			return new IntegerConstant(single().intValue());
		} else if (index instanceof Expression && LongStream.range(0, lengthLong()).allMatch(i -> valueAt(i).doubleValue() == i)) {
			return (Expression<? extends Number>) index;
		} else {
			return getExpression((Expression) index, ((Expression) index).isInt());
		}
	}


	/**
	 * {@inheritDoc}
	 *
	 * <p>Generates a Base64-encoded signature string that uniquely identifies this sequence's values.
	 * The signature is computed by converting all values to doubles and encoding the byte representation.
	 *
	 * <p>For constant sequences (single value), only the single value is encoded, making the
	 * signature compact regardless of the logical sequence length.
	 *
	 * <p>This method tracks its execution time using the {@link IndexSequence#timing} metric.
	 *
	 * @return a Base64-encoded string representing the sequence values
	 */
	@Override
	public String signature() {
		long start = System.nanoTime();

		try {
			if (single() == null) {
				ByteBuffer byteBuffer = ByteBuffer.allocate(Double.SIZE / Byte.SIZE * length());
				DoubleBuffer doubleBuffer = byteBuffer.asDoubleBuffer();
				doubleBuffer.put(doubleStream().toArray());
				return encoder.encodeToString(byteBuffer.array());
			} else {
				ByteBuffer byteBuffer = ByteBuffer.allocate(Double.SIZE / Byte.SIZE);
				DoubleBuffer doubleBuffer = byteBuffer.asDoubleBuffer();
				doubleBuffer.put(single().doubleValue());
				return encoder.encodeToString(byteBuffer.array());
			}
		} finally {
			timing.addEntry("signature", System.nanoTime() - start);
		}
	}

	/**
	 * Creates an IndexSequence by evaluating an expression with the given index values.
	 *
	 * <p>This factory method applies the provided {@code IndexValues} to the expression,
	 * creating a new expression with substituted values, and then generates a sequence
	 * by evaluating that expression for each index position.
	 *
	 * @param exp the expression to evaluate
	 * @param values the index values to substitute into the expression
	 * @param len the length of the resulting sequence
	 * @return an IndexSequence representing the evaluated expression values
	 * @see Expression#sequence(Index, long)
	 * @see IndexValues#apply(Expression)
	 */
	public static IndexSequence of(Expression<?> exp, IndexValues values, long len) {
		return values.apply(exp).sequence(new KernelIndex(), len);
	}

	/**
	 * Creates an IndexSequence by evaluating a SequenceGenerator for each index position.
	 *
	 * <p>This factory method creates a sequence by evaluating the source generator
	 * at each index position from 0 to len-1. The evaluation is performed in parallel
	 * for efficiency.
	 *
	 * @param source the sequence generator to evaluate
	 * @param index the index variable to use when evaluating the generator
	 * @param len the length of the resulting sequence
	 * @return an IndexSequence containing the evaluated values
	 * @throws IllegalArgumentException if len exceeds {@code Integer.MAX_VALUE}
	 * @see SequenceGenerator#value(IndexValues)
	 */
	public static IndexSequence of(SequenceGenerator source, Index index, long len) {
		if (len > Integer.MAX_VALUE)
			throw new IllegalArgumentException();

		return of(IntStream.range(0, Math.toIntExact(len)).parallel()
				.mapToObj(i -> source.value(new IndexValues().put(index, i))).toArray(Number[]::new));
	}

	/**
	 * Creates an IndexSequence from an array of Number values with inferred type.
	 *
	 * <p>The numeric type is inferred from the values. The resulting sequence
	 * has granularity 1 and length equal to the array length.
	 *
	 * @param values the array of numeric values; must not be empty
	 * @return an IndexSequence containing the provided values
	 * @throws IllegalArgumentException if values is empty
	 */
	public static IndexSequence of(Number[] values) {
		return of(null, values);
	}

	/**
	 * Creates an IndexSequence from a primitive int array.
	 *
	 * <p>This convenience method converts the int array to Integer objects and
	 * creates a sequence with {@code Integer.class} as the type. The resulting
	 * sequence has granularity 1 and length equal to the array length.
	 *
	 * <p>Example:
	 * <pre>{@code
	 * IndexSequence seq = ArrayIndexSequence.of(new int[]{0, 1, 2, 3, 4});
	 * // seq.valueAt(2) returns 2
	 * }</pre>
	 *
	 * @param values the array of int values; must not be empty
	 * @return an IndexSequence containing the provided values as Integers
	 * @throws IllegalArgumentException if values is empty
	 */
	public static IndexSequence of(int[] values) {
		return ArrayIndexSequence.of(Integer.class,
				IntStream.of(values).boxed().toArray(Number[]::new));
	}

	/**
	 * Creates an IndexSequence from an array of Number values with explicit type.
	 *
	 * <p>The resulting sequence has granularity 1 and length equal to the array length.
	 *
	 * @param type the numeric type class (e.g., {@code Integer.class}, {@code Double.class}),
	 *             or {@code null} to infer from values
	 * @param values the array of numeric values; must not be empty
	 * @return an IndexSequence containing the provided values
	 * @throws IllegalArgumentException if values is empty
	 */
	public static IndexSequence of(Class<? extends Number> type, Number[] values) {
		return new ArrayIndexSequence((Class) type, values, 1, values.length);
	}

	/**
	 * Creates an IndexSequence from an array of Number values with explicit type and length.
	 *
	 * <p>The logical length may exceed the array length; in that case, values are accessed
	 * cyclically using the modulus of the array length. The resulting sequence has granularity 1.
	 *
	 * @param type the numeric type class (e.g., {@code Integer.class}, {@code Double.class}),
	 *             or {@code null} to infer from values
	 * @param values the array of numeric values; must not be empty
	 * @param len the logical length of the sequence; positions 0 to len-1 are valid
	 * @return an IndexSequence containing the provided values
	 * @throws IllegalArgumentException if values is empty or len is less than 1
	 */
	public static IndexSequence of(Class<? extends Number> type, Number[] values, long len) {
		return new ArrayIndexSequence((Class) type, values, 1, len);
	}

	/**
	 * Creates an IndexSequence from an array of Number values with explicit type, granularity, and length.
	 *
	 * <p>This is the most flexible factory method, allowing full control over all sequence parameters.
	 * The granularity determines how many consecutive index positions map to the same stored value.
	 *
	 * <p>Example with granularity 2:
	 * <pre>{@code
	 * IndexSequence seq = ArrayIndexSequence.of(Integer.class, new Number[]{10, 20, 30}, 2, 6);
	 * // seq.valueAt(0) == seq.valueAt(1) == 10
	 * // seq.valueAt(2) == seq.valueAt(3) == 20
	 * // seq.valueAt(4) == seq.valueAt(5) == 30
	 * }</pre>
	 *
	 * @param type the numeric type class (e.g., {@code Integer.class}, {@code Double.class}),
	 *             or {@code null} to infer from values
	 * @param values the array of numeric values; must not be empty
	 * @param granularity the number of consecutive index positions that map to the same value;
	 *                    must be positive
	 * @param len the logical length of the sequence; positions 0 to len-1 are valid
	 * @return an IndexSequence containing the provided values with the specified granularity
	 * @throws IllegalArgumentException if values is empty or len is less than 1
	 */
	public static IndexSequence of(Class<? extends Number> type, Number[] values,
								   int granularity, long len) {
		return new ArrayIndexSequence((Class) type, values, granularity, len);
	}

	/**
	 * Creates a constant IndexSequence where all positions return the same value.
	 *
	 * <p>This factory method creates an efficient constant sequence that stores only
	 * a single value, regardless of the logical length. It is memory-efficient for
	 * representing large sequences of identical values.
	 *
	 * <p>Example:
	 * <pre>{@code
	 * IndexSequence constant = ArrayIndexSequence.of(42, 1000000);
	 * // constant.valueAt(0) == constant.valueAt(999999) == 42
	 * // constant.isConstant() returns true
	 * }</pre>
	 *
	 * @param value the constant value to return for all index positions
	 * @param len the logical length of the sequence; positions 0 to len-1 are valid
	 * @return a constant IndexSequence that returns the same value for all positions
	 * @throws IllegalArgumentException if len is less than 1
	 */
	public static IndexSequence of(Number value, long len) {
		return new ArrayIndexSequence(value, len);
	}
}
