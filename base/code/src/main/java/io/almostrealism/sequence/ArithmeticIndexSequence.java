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

package io.almostrealism.sequence;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;

import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * An {@link IndexSequence} implementation that efficiently represents arithmetic sequences.
 *
 * <p>{@code ArithmeticIndexSequence} represents sequences that follow the pattern:
 * <pre>{@code
 * value(pos) = offset + scale * ((pos % mod) / granularity)
 * }</pre>
 *
 * <p>This representation is memory-efficient as it stores only the sequence parameters
 * rather than all individual values. It is especially useful for representing index
 * patterns in kernel operations where memory access follows regular arithmetic progressions.</p>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>offset</b>: The constant added to all values (initial value when scale is 1)</li>
 *   <li><b>scale</b>: The multiplier applied to the computed position</li>
 *   <li><b>granularity</b>: How many consecutive indices share the same value (integer division factor)</li>
 *   <li><b>mod</b>: The modulus applied to the position (cycle length); never greater than the length,
 *       since a modulus of at least the length never wraps and is stored as the length itself</li>
 *   <li><b>len</b>: The total length of the sequence</li>
 * </ul>
 *
 * <h2>Example Sequences</h2>
 * <table border="1">
 *   <tr><th>Parameters</th><th>Sequence</th></tr>
 *   <tr><td>scale=1, granularity=1, len=4, mod=4</td><td>[0, 1, 2, 3]</td></tr>
 *   <tr><td>scale=2, granularity=1, len=4, mod=4</td><td>[0, 2, 4, 6]</td></tr>
 *   <tr><td>offset=10, scale=1, granularity=1, len=4, mod=4</td><td>[10, 11, 12, 13]</td></tr>
 *   <tr><td>scale=1, granularity=2, len=6, mod=6</td><td>[0, 0, 1, 1, 2, 2]</td></tr>
 *   <tr><td>scale=1, granularity=1, len=6, mod=3</td><td>[0, 1, 2, 0, 1, 2]</td></tr>
 * </table>
 *
 * <h2>Exact algebra</h2>
 * <p>{@link #plus(ArithmeticIndexSequence)}, {@link #scaled(long)}, {@link #negated()},
 * {@link #dividedExactly(long)} and {@link #modExactly(long)} combine sequences
 * symbolically and return {@code null} whenever the result is not itself an arithmetic
 * sequence under integer arithmetic. They are how
 * {@link Expression#arithmeticSequence(Index, long)} derives the progression an
 * expression follows without evaluating it, so every rule is exact for all non-negative
 * positions: nothing here rounds or approximates.</p>
 *
 * <h2>Expression Generation</h2>
 * <p>When {@link #enableAutoExpression} is {@code true} (the default), the
 * {@link #getExpression(Expression, boolean)} method generates an optimized expression
 * that computes values using the formula rather than looking them up. This is critical
 * for efficient code generation in kernel operations.</p>
 *
 * @author Michael Murray
 * @see IndexSequence
 * @see ArrayIndexSequence
 */
public class ArithmeticIndexSequence implements IndexSequence, ExpressionFeatures {

	/**
	 * Flag to enable automatic expression generation from arithmetic sequences.
	 *
	 * <p>When {@code true}, {@link #getExpression(Expression, boolean)} generates an
	 * optimized mathematical expression. When {@code false}, it falls back to the
	 * default pattern-detection-based approach in {@link IndexSequence}.</p>
	 */
	public static boolean enableAutoExpression = true;

	/** The constant offset added to all computed values. */
	private final long offset;

	/** The multiplier applied to the computed position. */
	private final long scale;

	/**
	 * The number of consecutive index positions that share the same value.
	 * The position formula divides by this value using integer division.
	 */
	private final long granularity;

	/**
	 * The modulus (cycle length) of the sequence, at most {@link #len}.
	 * Position values are reduced modulo this value before further computation.
	 */
	private final long mod;

	/** The total length of the sequence (number of elements). */
	private final long len;

	/**
	 * Creates an arithmetic index sequence with no offset and mod equal to len.
	 *
	 * <p>This constructor creates a sequence where values cycle through
	 * the full length without repeating.</p>
	 *
	 * @param scale the multiplier for computed positions
	 * @param granularity the number of consecutive positions with the same value
	 * @param len the total length and modulus of the sequence
	 */
	public ArithmeticIndexSequence(long scale, long granularity, long len) {
		this(0, scale, granularity, len, len);
	}

	/**
	 * Creates an arithmetic index sequence with no offset.
	 *
	 * @param scale the multiplier for computed positions
	 * @param granularity the number of consecutive positions with the same value
	 * @param mod the modulus (cycle length) of the sequence
	 * @param len the total length of the sequence
	 */
	public ArithmeticIndexSequence(long scale, long granularity, long mod, long len) {
		this(0, scale, granularity, mod, len);
	}

	/**
	 * Creates an arithmetic index sequence with all parameters specified.
	 *
	 * <p>Values are computed using the formula:
	 * <pre>{@code
	 * value(pos) = offset + scale * ((pos % mod) / granularity)
	 * }</pre>
	 *
	 * <p>A modulus of at least {@code len} never affects any position of the sequence
	 * and is stored as {@code len}, so that sequences which agree on every position
	 * also agree on their parameters.</p>
	 *
	 * @param offset the constant offset added to all values
	 * @param scale the multiplier for computed positions
	 * @param granularity the number of consecutive positions with the same value; must be positive
	 * @param mod the modulus (cycle length) of the sequence; must be positive
	 * @param len the total length of the sequence; must be positive
	 * @throws IllegalArgumentException if granularity, mod or len is not positive
	 */
	public ArithmeticIndexSequence(long offset, long scale, long granularity, long mod, long len) {
		if (granularity <= 0 || mod <= 0 || len <= 0) {
			throw new IllegalArgumentException("Granularity, modulus and length must be positive");
		}

		this.offset = offset;
		this.scale = scale;
		this.granularity = granularity;
		this.mod = Math.min(mod, len);
		this.len = len;
	}

	/**
	 * Returns the constant offset added to every value.
	 *
	 * @return the offset
	 */
	public long getOffset() { return offset; }

	/**
	 * Returns the multiplier applied to the computed position.
	 *
	 * @return the scale
	 */
	public long getScale() { return scale; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Computes the value using the formula:
	 * {@code offset + scale * ((pos % mod) / granularity)}</p>
	 */
	@Override
	public Number valueAt(long pos) {
		pos = (pos % mod) / granularity;
		return offset + scale * pos;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Computed from the parameters: the smaller of the values at step zero and at
	 * the last step of a cycle.</p>
	 */
	@Override
	public long min() {
		return Math.min(offset, offset + scale * lastStep());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Computed from the parameters: the larger of the values at step zero and at
	 * the last step of a cycle.</p>
	 */
	@Override
	public long max() {
		return Math.max(offset, offset + scale * lastStep());
	}

	/**
	 * Returns the largest step {@code (pos % mod) / granularity} any position reaches.
	 */
	private long lastStep() {
		return (Math.min(mod, len) - 1) / granularity;
	}

	/**
	 * Returns {@code true} if every value of this sequence is a multiple of the operand,
	 * which holds exactly when both the offset and the scale are.
	 *
	 * @param operand the candidate divisor; must not be zero
	 * @return whether every value is divisible by {@code operand}
	 */
	public boolean isMultipleOf(long operand) {
		return offset % operand == 0 && scale % operand == 0;
	}

	/**
	 * Returns the largest value every element of this sequence is a multiple of: the
	 * greatest common divisor of the offset and the scale, or zero for the all-zero
	 * sequence.
	 *
	 * @return the common factor of all values
	 */
	public long commonFactor() {
		long a = Math.abs(offset);
		long b = Math.abs(scale);

		while (b != 0) {
			long t = a % b;
			a = b;
			b = t;
		}

		return a;
	}

	/**
	 * Returns the sequence whose every value is this sequence's value plus the
	 * other's, or {@code null} if that sum is not an arithmetic sequence.
	 *
	 * <p>A constant sequence (scale zero) may be added to anything; otherwise the two
	 * sequences must step at the same granularity and wrap at the same modulus.</p>
	 *
	 * @param other the sequence to add; must have the same length
	 * @return the sum, or {@code null} if it cannot be represented
	 * @throws IllegalArgumentException if the lengths differ
	 */
	public ArithmeticIndexSequence plus(ArithmeticIndexSequence other) {
		if (other.len != len) {
			throw new IllegalArgumentException("Sequence lengths differ");
		}

		if (other.scale == 0) {
			return new ArithmeticIndexSequence(offset + other.offset, scale, granularity, mod, len);
		} else if (scale == 0) {
			return new ArithmeticIndexSequence(offset + other.offset, other.scale, other.granularity, other.mod, len);
		} else if (granularity == other.granularity && mod == other.mod) {
			return new ArithmeticIndexSequence(offset + other.offset, scale + other.scale, granularity, mod, len);
		}

		return null;
	}

	/**
	 * Returns the sequence whose every value is this sequence's value times the operand.
	 *
	 * @param operand the multiplier
	 * @return the scaled sequence
	 */
	public ArithmeticIndexSequence scaled(long operand) {
		return new ArithmeticIndexSequence(offset * operand, scale * operand, granularity, mod, len);
	}

	/**
	 * Returns the sequence whose every value is the negation of this sequence's value.
	 *
	 * @return the negated sequence
	 */
	public ArithmeticIndexSequence negated() {
		return new ArithmeticIndexSequence(-offset, -scale, granularity, mod, len);
	}

	/**
	 * Returns the sequence whose every value is this sequence's value divided by the
	 * operand with truncating integer division, or {@code null} if that is not an
	 * arithmetic sequence.
	 *
	 * <p>Writing the value as {@code offset + scale * k} with {@code k >= 0}, the
	 * quotient is exact when the divisor divides both offset and scale, and when the
	 * offset is zero and the scale divides the divisor, in which case the division
	 * coarsens the granularity instead. A negative divisor negates the result, since
	 * truncating division is symmetric.</p>
	 *
	 * @param operand the divisor; must not be zero
	 * @return the quotient, or {@code null} if it cannot be represented
	 * @throws IllegalArgumentException if {@code operand} is zero
	 */
	public ArithmeticIndexSequence dividedExactly(long operand) {
		if (operand == 0) {
			throw new IllegalArgumentException("Division by zero");
		}

		if (operand < 0) {
			ArithmeticIndexSequence positive = dividedExactly(-operand);
			return positive == null ? null : positive.negated();
		}

		if (scale == 0) {
			return new ArithmeticIndexSequence(offset / operand, 0, granularity, mod, len);
		} else if (offset % operand == 0 && scale % operand == 0) {
			return new ArithmeticIndexSequence(offset / operand, scale / operand, granularity, mod, len);
		} else if (offset == 0 && operand % Math.abs(scale) == 0) {
			long coarser;

			try {
				coarser = Math.multiplyExact(granularity, operand / Math.abs(scale));
			} catch (ArithmeticException e) {
				return null;
			}

			return new ArithmeticIndexSequence(0, Long.signum(scale), coarser, mod, len);
		}

		return null;
	}

	/**
	 * Returns the sequence whose every value is this sequence's value modulo the
	 * operand, or {@code null} if that is not an arithmetic sequence.
	 *
	 * <p>Only non-negative sequences (offset and scale both at least zero) are reduced,
	 * so that truncating and flooring modulus agree. Writing the value as
	 * {@code offset + scale * k}: when the operand divides the scale the result is the
	 * constant {@code offset % operand}; when the scale divides the operand and the
	 * offset is a multiple of {@code scale * (operand / scale)}, the result is
	 * {@code scale * (k % (operand / scale))}, which wraps every
	 * {@code granularity * (operand / scale)} positions provided that period is
	 * compatible with the existing modulus. A period too large to represent is treated
	 * as inexact rather than allowed to wrap.</p>
	 *
	 * @param operand the modulus; must be positive
	 * @return the remainder sequence, or {@code null} if it cannot be represented
	 * @throws IllegalArgumentException if {@code operand} is not positive
	 */
	public ArithmeticIndexSequence modExactly(long operand) {
		if (operand <= 0) {
			throw new IllegalArgumentException("Modulus must be positive");
		}

		if (offset < 0 || scale < 0) return null;

		if (scale == 0 || scale % operand == 0) {
			return new ArithmeticIndexSequence(offset % operand, 0, granularity, mod, len);
		} else if (operand % scale == 0) {
			long steps = operand / scale;
			if (offset % operand != 0) return null;

			long period;

			try {
				period = Math.multiplyExact(granularity, steps);
			} catch (ArithmeticException e) {
				return null;
			}

			if (mod < len && mod % period != 0) return null;

			return new ArithmeticIndexSequence(0, scale, granularity, Math.min(period, mod), len);
		}

		return null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to {@link #scaled(long)}.</p>
	 */
	@Override
	public IndexSequence multiply(long operand) {
		return scaled(operand);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to {@link #dividedExactly(long)}, falling back to element-wise
	 * division when the quotient is not an arithmetic sequence.</p>
	 */
	@Override
	public IndexSequence divide(long operand) {
		ArithmeticIndexSequence exact = dividedExactly(operand);
		return exact == null ? IndexSequence.super.divide(operand) : exact;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to {@link #negated()}.</p>
	 */
	@Override
	public IndexSequence minus() {
		return negated();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to {@link #modExactly(long)}, falling back to element-wise modulo
	 * when the remainder is not an arithmetic sequence.</p>
	 */
	@Override
	public IndexSequence mod(long m) {
		ArithmeticIndexSequence exact = m > 0 ? modExactly(m) : null;
		return exact == null ? IndexSequence.super.mod(m) : exact;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws UnsupportedOperationException always; subsetting is not supported
	 */
	@Override
	public IndexSequence subset(long len) {
		throw new UnsupportedOperationException();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An arithmetic sequence is constant when it has a single position, when its
	 * scale is zero, or when its granularity is at least its modulus so that every
	 * position maps to step zero.</p>
	 */
	@Override
	public boolean isConstant() {
		return len == 1 || scale == 0 || granularity >= mod;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getGranularity() {
		return Math.toIntExact(granularity);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getMod() {
		return mod;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>When {@link #enableAutoExpression} is {@code true}, generates an expression
	 * directly from the parameters: a constant when the sequence is constant, otherwise
	 * {@code ((index % mod) / granularity) * scale + offset} with the modulus omitted when
	 * it never wraps, the division omitted at unit granularity, and the multiplication
	 * omitted at unit scale.</p>
	 */
	@Override
	public Expression getExpression(Expression index, boolean isInt) {
		if (!enableAutoExpression) return IndexSequence.super.getExpression(index, isInt);

		if (isConstant()) {
			return isInt ? e(offset) : e((double) offset);
		}

		Expression pos = index;
		if (mod < len) pos = pos.imod(mod);
		if (granularity > 1) pos = pos.toInt().divide(e(granularity));

		Expression r = pos;
		if (Math.abs(scale) != 1) r = r.multiply(e(Math.abs(scale)));
		if (scale < 0) r = r.minus();
		if (offset != 0) r = r.add(e(offset));

		return r;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a stream of the distinct values in one cycle of the sequence
	 * (up to the modulus). If the offset is non-zero, falls back to the default
	 * implementation.</p>
	 */
	@Override
	public Stream<Number> values() {
		if (offset != 0) return IndexSequence.super.values();

		return LongStream.range(0, mod).mapToObj(this::valueAt);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long lengthLong() {
		return len;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws UnsupportedOperationException always; use {@link #values()} or {@link #valueAt(long)} instead
	 */
	@Override
	public Number[] toArray() {
		throw new UnsupportedOperationException();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code Integer.class} as arithmetic sequences produce integer values
	 */
	@Override
	public Class<? extends Number> getType() {
		return Integer.class;
	}

	/**
	 * Returns a hash code based on the modulus value.
	 *
	 * @return a hash code for this sequence
	 */
	@Override
	public int hashCode() {
		return (int) mod;
	}

	/**
	 * Tests equality with another object.
	 *
	 * <p>Two {@code ArithmeticIndexSequence} instances are equal if they have
	 * the same offset, scale, granularity, modulus, and length.</p>
	 *
	 * @param obj the object to compare with
	 * @return {@code true} if the objects are equal arithmetic index sequences
	 */
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ArithmeticIndexSequence)) return false;

		ArithmeticIndexSequence other = (ArithmeticIndexSequence) obj;
		return offset == other.offset && scale == other.scale &&
				granularity == other.granularity && mod == other.mod && len == other.len;
	}

	@Override
	public String toString() {
		return offset + " + " + scale + " * ((i % " + mod + ") / " + granularity + ") [" + len + "]";
	}
}
