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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
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
 * <p>{@link #plus(ArithmeticIndexSequence)}, {@link #sumOf(List)}, {@link #scaled(long)},
 * {@link #negated()}, {@link #dividedExactly(long)}, {@link #modExactly(long)},
 * {@link #quotientOf(List, long)} and {@link #remainderOf(List, long)} combine sequences
 * symbolically and return {@code null} whenever the result is not itself an arithmetic
 * sequence under integer arithmetic. They are how
 * {@link Expression#arithmeticSequence(Index, long)} derives the progression an
 * expression follows without evaluating it, so every rule is exact for all non-negative
 * positions: nothing here rounds or approximates. The rules taking a list reason about
 * the terms of a sum separately, so a quotient or remainder can be a sequence even when
 * the sum itself is not.</p>
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
	 * The modulus (cycle length) of the sequence, at most {@link #len} (one for the
	 * empty sequence).
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
	 * <p>A length of zero is the empty progression, which has no values; see
	 * {@link #range(long)}.</p>
	 *
	 * @param offset the constant offset added to all values
	 * @param scale the multiplier for computed positions
	 * @param granularity the number of consecutive positions with the same value; must be positive
	 * @param mod the modulus (cycle length) of the sequence; must be positive
	 * @param len the total length of the sequence; must not be negative
	 * @throws IllegalArgumentException if granularity or mod is not positive, or len is negative
	 */
	public ArithmeticIndexSequence(long offset, long scale, long granularity, long mod, long len) {
		if (granularity <= 0 || mod <= 0 || len < 0) {
			throw new IllegalArgumentException("Granularity and modulus must be positive and length must not be negative");
		}

		this.offset = offset;
		this.scale = scale;
		this.granularity = granularity;
		this.mod = Math.min(mod, Math.max(len, 1));
		this.len = len;
	}

	/**
	 * Returns the progression {@code 0, 1, ..., count - 1}, which is the empty progression
	 * when {@code count} is zero.
	 *
	 * @param count the number of values; must not be negative
	 * @return the consecutive integers from zero below {@code count}
	 * @throws IllegalArgumentException if {@code count} is negative
	 */
	public static ArithmeticIndexSequence range(long count) {
		return new ArithmeticIndexSequence(0, 1, 1, Math.max(count, 1), count);
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
	 * the last step of a cycle. A progression whose values wrap around the {@code long}
	 * range has no such bound, so its minimum is found by the general method.</p>
	 */
	@Override
	public long min() {
		requireValues();
		if (wraps()) return IndexSequence.super.min();
		return Math.min(offset, offset + scale * lastStep());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Computed from the parameters: the larger of the values at step zero and at
	 * the last step of a cycle. A progression whose values wrap around the {@code long}
	 * range has no such bound, so its maximum is found by the general method.</p>
	 */
	@Override
	public long max() {
		requireValues();
		if (wraps()) return IndexSequence.super.max();
		return Math.max(offset, offset + scale * lastStep());
	}

	/**
	 * Returns the largest step {@code (pos % mod) / granularity} any position reaches.
	 */
	private long lastStep() {
		return (mod - 1) / granularity;
	}

	/**
	 * Fails for the empty progression, which has no minimum or maximum.
	 *
	 * @throws NoSuchElementException if this sequence has no positions
	 */
	private void requireValues() {
		if (len == 0) throw new NoSuchElementException("Empty sequence");
	}

	/**
	 * Returns whether {@link #valueAt(long)} leaves the {@code long} range at some step,
	 * so that its values wrap around and no longer form a progression. The values of a
	 * cycle are monotonic in exact arithmetic, so they wrap exactly when the value at the
	 * last step does.
	 *
	 * @return {@code true} if {@code offset + scale * lastStep()} overflows a {@code long}
	 */
	private boolean wraps() {
		long steps = lastStep();
		long product = scale * steps;
		if (Math.multiplyHigh(scale, steps) != (product >> 63)) return true;

		long sum = offset + product;
		return ((offset ^ sum) & (product ^ sum)) < 0;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Computed from the parameters: the number of steps a cycle reaches, or one when
	 * the sequence is constant. A progression whose values wrap around the {@code long}
	 * range repeats with the period {@link #wrapPeriod()} of its steps, so it takes the
	 * smaller of that period and the number of steps.</p>
	 */
	@Override
	public long distinctCount() {
		if (len == 0) return 0;
		if (isConstant()) return 1;

		long steps = lastStep() + 1;
		if (!wraps()) return steps;

		long period = wrapPeriod();
		return period <= 0 ? steps : Math.min(steps, period);
	}

	/**
	 * Returns the number of steps after which {@code scale * step} repeats in wrapping
	 * {@code long} arithmetic: {@code 2^(64 - t)} where {@code t} is the number of
	 * trailing zero bits of the scale. A period of {@code 2^63} or more does not fit in a
	 * {@code long} and is reported as zero; no progression has that many steps, so its
	 * values never repeat.
	 *
	 * @return the period of the steps, or zero if it exceeds the {@code long} range
	 */
	private long wrapPeriod() {
		int t = Long.numberOfTrailingZeros(scale);
		return t <= 1 ? 0 : 1L << (64 - t);
	}

	/**
	 * Returns whether some step of a progression whose values wrap around the
	 * {@code long} range takes the given value, by solving
	 * {@code scale * step == value - offset} modulo {@code 2^64} rather than enumerating
	 * the steps. With {@code t} the trailing zero bits of the scale, a solution exists
	 * exactly when the difference is a multiple of {@code 2^t}, and the smallest one is
	 * the quotient of the difference and the scale, both divided by {@code 2^t}, taken
	 * with the multiplicative inverse of the (then odd) scale modulo {@code 2^(64 - t)}.
	 *
	 * @param value the candidate value
	 * @return {@code true} if a step at most {@link #lastStep()} takes {@code value}
	 */
	private boolean wrappedContains(long value) {
		long difference = value - offset;
		int t = Long.numberOfTrailingZeros(scale);
		if (Long.numberOfTrailingZeros(difference) < t) return false;

		long odd = scale >>> t;
		long inverse = odd;
		for (int i = 0; i < 5; i++) {
			inverse *= 2 - odd * inverse;
		}

		long step = (difference >>> t) * inverse;
		if (t > 0) step &= -1L >>> t;
		return Long.compareUnsigned(step, lastStep()) <= 0;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Computed from the parameters: the value is contained when it lies between the
	 * minimum and the maximum and differs from the offset by a multiple of the scale.
	 * A progression whose values wrap around the {@code long} range is decided by solving
	 * for the step in wrapping arithmetic, which matches the values
	 * {@link #valueAt(long)} actually takes.</p>
	 */
	@Override
	public boolean contains(long value) {
		if (len == 0) return false;
		if (isConstant()) return value == offset;
		if (wraps()) return wrappedContains(value);
		if (value < min() || value > max()) return false;

		// Within the range of a progression that does not wrap, the
		// difference from the offset cannot overflow
		long difference = value - offset;
		return difference % scale == 0;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>A progression with unit scale whose values do not wrap takes every integer
	 * between its minimum and its maximum, so it contains the values of an integer
	 * sequence exactly when it contains that sequence's minimum and maximum, and no value
	 * of the other sequence is enumerated. Any other case is decided by the general
	 * test.</p>
	 */
	@Override
	public boolean containsAll(IndexSequence other) {
		if (other.isEmpty()) return true;
		if (len == 0) return false;

		boolean integral = other.getType() == Integer.class || other.getType() == Long.class;

		if (integral && !isConstant() && Math.abs(scale) == 1 && !wraps()) {
			return contains(other.min()) && contains(other.max());
		}

		return IndexSequence.super.containsAll(other);
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
		return ExpressionFeatures.gcd(offset, scale);
	}

	/**
	 * Returns the sequence whose every value is this sequence's value plus the
	 * other's, or {@code null} if that sum is not an arithmetic sequence.
	 *
	 * <p>A constant sequence (scale zero) may be added to anything; otherwise the two
	 * sequences must step at the same granularity and wrap at the same modulus. The
	 * combined offset and scale are computed with overflow checking; an overflowing
	 * sum is treated as not representable rather than silently wrapping into an
	 * incorrect sequence.</p>
	 *
	 * @param other the sequence to add; must have the same length
	 * @return the sum, or {@code null} if it cannot be represented
	 * @throws IllegalArgumentException if the lengths differ
	 */
	public ArithmeticIndexSequence plus(ArithmeticIndexSequence other) {
		if (other.len != len) {
			throw new IllegalArgumentException("Sequence lengths differ");
		}

		try {
			if (other.scale == 0) {
				return new ArithmeticIndexSequence(Math.addExact(offset, other.offset), scale, granularity, mod, len);
			} else if (scale == 0) {
				return new ArithmeticIndexSequence(Math.addExact(offset, other.offset), other.scale, other.granularity, other.mod, len);
			} else if (granularity == other.granularity && mod == other.mod) {
				return new ArithmeticIndexSequence(Math.addExact(offset, other.offset), Math.addExact(scale, other.scale), granularity, mod, len);
			}
		} catch (ArithmeticException e) {
			return null;
		}

		return null;
	}

	/**
	 * Returns the sequence whose every value is the sum of the given sequences' values,
	 * adding them in order under {@link #plus(ArithmeticIndexSequence)}, or {@code null}
	 * if a partial sum is not an arithmetic sequence.
	 *
	 * @param terms the sequences to add; must not be empty, and must all have the same length
	 * @return their sum, or {@code null} if it cannot be represented
	 */
	public static ArithmeticIndexSequence sumOf(List<ArithmeticIndexSequence> terms) {
		ArithmeticIndexSequence result = terms.get(0);

		for (int i = 1; result != null && i < terms.size(); i++) {
			result = result.plus(terms.get(i));
		}

		return result;
	}

	/**
	 * Returns the sequence whose every value is this sequence's value times the operand,
	 * or {@code null} if the scaled offset or scale would overflow a {@code long}.
	 *
	 * @param operand the multiplier
	 * @return the scaled sequence, or {@code null} if it cannot be represented
	 */
	public ArithmeticIndexSequence scaled(long operand) {
		try {
			return new ArithmeticIndexSequence(
					Math.multiplyExact(offset, operand), Math.multiplyExact(scale, operand),
					granularity, mod, len);
		} catch (ArithmeticException e) {
			return null;
		}
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

		if (operand == Long.MIN_VALUE) {
			// -Long.MIN_VALUE is not representable as a positive long, so the
			// negate-and-recurse below would repeat forever; there is no
			// arithmetic sequence representation of this quotient
			return null;
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
	 * Returns the sequence whose every value is the sum of the given sequences' values
	 * divided by the divisor with truncating integer division, or {@code null} if that is
	 * not an arithmetic sequence.
	 *
	 * <p>The terms are added under {@link #sumOf(List)} and the sum is divided under
	 * {@link #dividedExactly(long)}. When the terms do not add up to an arithmetic
	 * sequence, the quotient may still be one. Write the sum as {@code T + R}, where every
	 * term of {@code T} is a non-negative multiple of some {@code c} that divides the
	 * divisor, and {@code R} is never negative and always below {@code c}. Then the
	 * quotient of the sum equals the quotient of {@code T} alone: {@code T} lands on a
	 * multiple of {@code c}, and adding less than {@code c} cannot carry it past the next
	 * multiple of the divisor. Both parts must be non-negative because division truncates
	 * toward zero: {@code (-4 + 3) / 4} is {@code 0} while {@code -4 / 4} is {@code -1}.
	 * The terms sharing a factor with the divisor form {@code T}, and {@code c} is the
	 * largest factor of the divisor dividing all of them. Negative terms, a remainder whose
	 * bound cannot be added without overflow, and a divisor that shares no factor with any
	 * term all leave the remainder in place. A divisor of {@link Long#MIN_VALUE} has no
	 * representable quotient, as under {@link #dividedExactly(long)}.</p>
	 *
	 * <p>The rules hold for the exact integer sum, so nothing is derived when the sum
	 * evaluated in wrapping {@code long} arithmetic may differ from it; see
	 * {@link #isSumExact(List)}. Empty terms have the empty quotient.</p>
	 *
	 * @param terms the sequences whose sum is divided; must not be empty, and must all have the same length
	 * @param divisor the divisor; must not be zero
	 * @return the quotient, or {@code null} if it cannot be represented
	 * @throws IllegalArgumentException if {@code divisor} is zero
	 */
	public static ArithmeticIndexSequence quotientOf(List<ArithmeticIndexSequence> terms, long divisor) {
		if (divisor == 0) {
			throw new IllegalArgumentException("Division by zero");
		} else if (divisor == Long.MIN_VALUE) {
			// No quotient by -2^63 is representable (see dividedExactly), and its absolute
			// value would overflow back to a negative divisor
			return null;
		}

		if (terms.get(0).lengthLong() == 0) return range(0);
		if (!isSumExact(terms)) return null;

		ArithmeticIndexSequence numerator = sumOf(terms);

		if (numerator == null && terms.size() > 1) {
			numerator = sumOf(withoutBoundedRemainder(terms, Math.abs(divisor)));
		}

		return numerator == null ? null : numerator.dividedExactly(divisor);
	}

	/**
	 * Returns the terms {@code T} that determine the quotient of the sum of the given
	 * sequences by the divisor under the bounded-remainder rule of
	 * {@link #quotientOf(List, long)}, or the terms unchanged if no remainder can be dropped.
	 *
	 * @param terms the sequences being added
	 * @param divisor the positive divisor
	 * @return the terms of {@code T}, or {@code terms} unchanged
	 */
	private static List<ArithmeticIndexSequence> withoutBoundedRemainder(List<ArithmeticIndexSequence> terms, long divisor) {
		List<ArithmeticIndexSequence> coarse = terms.stream()
				.filter(t -> ExpressionFeatures.gcd(t.commonFactor(), divisor) > 1).collect(Collectors.toList());
		if (coarse.isEmpty() || coarse.size() == terms.size()) return terms;
		if (coarse.stream().anyMatch(t -> t.min() < 0)) return terms;

		long c = coarse.stream().mapToLong(t -> ExpressionFeatures.gcd(t.commonFactor(), divisor)).reduce(divisor, ExpressionFeatures::gcd);
		long remainder = 0;

		try {
			for (ArithmeticIndexSequence t : terms) {
				if (coarse.contains(t)) continue;
				if (t.min() < 0) return terms;
				remainder = Math.addExact(remainder, t.max());
			}
		} catch (ArithmeticException e) {
			return terms;
		}

		return remainder < c ? coarse : terms;
	}

	/**
	 * Returns the sequence whose every value is the sum of the given sequences' values
	 * modulo the operand, or {@code null} if that is not an arithmetic sequence.
	 *
	 * <p>A term that is a multiple of the modulus at every position cannot affect the
	 * remainder, provided truncating and flooring remainders agree for both the full sum
	 * and the sum of the rest. A dropped negative multiple can matter:
	 * {@code (-4 + (i % 2)) % 2} is {@code 0, -1} while {@code (i % 2) % 2} is {@code 0, 1}.
	 * Multiples are therefore dropped only when every remaining term is non-negative and
	 * the sum of the minimums of all terms is non-negative, so the full sum is never
	 * negative either: {@code (x - 8) % 4} is {@code x % 4} when {@code x >= 8}. The
	 * remaining terms are added under {@link #sumOf(List)} and reduced under
	 * {@link #modExactly(long)}. When every term is a multiple of the modulus, the
	 * remainder is zero at every position.</p>
	 *
	 * <p>A term whose own values wrap around the {@code long} range is not a multiple of
	 * the modulus merely because its offset and scale are, and a wrapping sum has a
	 * different remainder from the exact one, so nothing is derived unless the sum is
	 * exact under {@link #isSumExact(List)}. Empty terms have the empty remainder.</p>
	 *
	 * @param terms the sequences whose sum is reduced; must not be empty, and must all have the same length
	 * @param operand the modulus; must be positive
	 * @return the remainder sequence, or {@code null} if it cannot be represented
	 * @throws IllegalArgumentException if {@code operand} is not positive
	 */
	public static ArithmeticIndexSequence remainderOf(List<ArithmeticIndexSequence> terms, long operand) {
		if (operand <= 0) {
			throw new IllegalArgumentException("Modulus must be positive");
		}

		if (terms.get(0).lengthLong() == 0) return range(0);
		if (!isSumExact(terms)) return null;

		List<ArithmeticIndexSequence> remaining = terms.stream()
				.filter(t -> !t.isMultipleOf(operand))
				.collect(Collectors.toList());

		if (remaining.isEmpty()) {
			long length = terms.get(0).lengthLong();
			return new ArithmeticIndexSequence(0, 0, 1, length, length);
		} else if (remaining.size() < terms.size() && !isNeverNegative(remaining, terms)) {
			return null;
		}

		ArithmeticIndexSequence dividend = sumOf(remaining);
		return dividend == null ? null : dividend.modExactly(operand);
	}

	/**
	 * Returns whether every remaining term is non-negative and the sum of all terms is
	 * never negative, judged by the sum of their minimums. The terms must already satisfy
	 * {@link #isSumExact(List)}, so that sum cannot overflow.
	 *
	 * @param remaining the terms kept after dropping multiples of the modulus
	 * @param terms all terms of the sum
	 * @return {@code true} if both the rest and the full sum are never negative
	 */
	private static boolean isNeverNegative(List<ArithmeticIndexSequence> remaining,
										   List<ArithmeticIndexSequence> terms) {
		if (remaining.stream().anyMatch(t -> t.min() < 0)) return false;
		return terms.stream().mapToLong(ArithmeticIndexSequence::min).sum() >= 0;
	}

	/**
	 * Returns whether the sum of the given non-empty sequences, evaluated position by
	 * position in wrapping {@code long} arithmetic, equals the exact integer sum at every
	 * position. That holds when no term's own values wrap and both the sum of the minimums
	 * and the sum of the maximums fit in a {@code long}: the exact sum then lies between
	 * them, and wrapping addition agrees with exact addition whenever the exact result is
	 * representable, whatever the order of the partial sums.
	 *
	 * @param terms the sequences being added; must not be empty sequences
	 * @return {@code true} if the sum never wraps
	 */
	private static boolean isSumExact(List<ArithmeticIndexSequence> terms) {
		long minimum = 0;
		long maximum = 0;

		try {
			for (ArithmeticIndexSequence t : terms) {
				if (t.wraps()) return false;
				minimum = Math.addExact(minimum, t.min());
				maximum = Math.addExact(maximum, t.max());
			}
		} catch (ArithmeticException e) {
			return false;
		}

		return true;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to {@link #scaled(long)}, falling back to element-wise
	 * multiplication when the scaled offset or scale would overflow.</p>
	 */
	@Override
	public IndexSequence multiply(long operand) {
		ArithmeticIndexSequence exact = scaled(operand);
		return exact == null ? IndexSequence.super.multiply(operand) : exact;
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
	 * <p>The first positions of an arithmetic sequence follow the same formula, so the
	 * subset is the sequence with the same parameters and the shorter length; a modulus
	 * that no longer wraps is stored as that length. Requesting the full length returns
	 * this sequence.</p>
	 *
	 * @throws IllegalArgumentException if {@code len} is not positive or exceeds the length
	 */
	@Override
	public IndexSequence subset(long len) {
		if (len <= 0 || len > this.len) {
			throw new IllegalArgumentException("Subset length " + len + " is outside [1, " + this.len + "]");
		}

		if (len == this.len) return this;
		return new ArithmeticIndexSequence(offset, scale, granularity, mod, len);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An arithmetic sequence is constant when it has a single position, when its
	 * scale is zero, or when its granularity is at least its modulus so that every
	 * position maps to step zero. The empty sequence takes no value at all, so it is
	 * never constant.</p>
	 */
	@Override
	public boolean isConstant() {
		if (len == 0) return false;
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
	 * (up to the modulus). A progression whose values wrap around the {@code long}
	 * range streams each of its {@link #distinctCount()} values once, one per step
	 * within a period, so that operations built on the values ({@link #min()},
	 * {@link #max()}, {@link #union(IndexSequence)}, {@link #containsAll(IndexSequence)})
	 * are bounded by the number of distinct values rather than by the length. Every other
	 * progression streams the positions of its first cycle, which take every value the
	 * sequence takes whatever its offset.</p>
	 */
	@Override
	public Stream<Number> values() {
		if (len == 0) return IndexSequence.super.values();

		if (wraps()) {
			return LongStream.range(0, distinctCount()).mapToObj(step -> offset + scale * step);
		}

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
	 * <p>Every position is computed from the parameters by {@link #valueAt(long)}.</p>
	 *
	 * @throws ArithmeticException if the length exceeds {@link Integer#MAX_VALUE}
	 */
	@Override
	public Number[] toArray() {
		return LongStream.range(0, length()).mapToObj(this::valueAt).toArray(Number[]::new);
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
