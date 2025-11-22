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
 * patterns in kernel operations where memory access follows regular arithmetic progressions.
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>offset</b>: The constant added to all values (initial value when scale is 1)</li>
 *   <li><b>scale</b>: The multiplier applied to the computed position</li>
 *   <li><b>granularity</b>: How many consecutive indices share the same value (integer division factor)</li>
 *   <li><b>mod</b>: The modulus applied to the position (cycle length)</li>
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
 * <h2>Expression Generation</h2>
 * <p>When {@link #enableAutoExpression} is {@code true} (the default), the
 * {@link #getExpression(Expression, boolean)} method generates an optimized expression
 * that computes values using the formula rather than looking them up. This is critical
 * for efficient code generation in kernel operations.
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
	 * default pattern-detection-based approach in {@link IndexSequence}.
	 */
	public static boolean enableAutoExpression = true;

	/**
	 * The constant offset added to all computed values.
	 */
	private long offset;

	/**
	 * The multiplier applied to the computed position.
	 */
	private long scale;

	/**
	 * The number of consecutive index positions that share the same value.
	 * The position formula divides by this value using integer division.
	 */
	private long granularity;

	/**
	 * The modulus (cycle length) of the sequence.
	 * Position values are reduced modulo this value before further computation.
	 */
	private long mod;

	/**
	 * The total length of the sequence (number of elements).
	 */
	private long len;

	/**
	 * Creates an arithmetic index sequence with no offset and mod equal to len.
	 *
	 * <p>This constructor creates a sequence where values cycle through
	 * the full length without repeating.
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
	 * @param offset the constant offset added to all values
	 * @param scale the multiplier for computed positions
	 * @param granularity the number of consecutive positions with the same value
	 * @param mod the modulus (cycle length) of the sequence
	 * @param len the total length of the sequence
	 */
	public ArithmeticIndexSequence(long offset, long scale, long granularity, long mod, long len) {
		this.offset = offset;
		this.scale = scale;
		this.granularity = granularity;
		this.mod = mod;
		this.len = len;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Computes the value using the formula:
	 * {@code offset + scale * ((pos % mod) / granularity)}
	 */
	@Override
	public Number valueAt(long pos) {
		pos = (pos % mod) / granularity;
		return offset + scale * pos;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a new {@code ArithmeticIndexSequence} with both offset and scale
	 * multiplied by the operand, preserving the arithmetic nature of the sequence.
	 */
	@Override
	public IndexSequence multiply(long operand) {
		return new ArithmeticIndexSequence(offset * operand, scale * operand, granularity, mod, len);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>If the sequence has no offset, unit scale, and unit granularity, returns a new
	 * {@code ArithmeticIndexSequence} with increased granularity. Otherwise, falls back
	 * to the default element-wise division.
	 */
	@Override
	public IndexSequence divide(long operand) {
		if (offset != 0 || granularity != 1 || scale != 1) {
			return IndexSequence.super.divide(operand);
		}

		return new ArithmeticIndexSequence(0, scale, granularity * operand, mod, len);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a new {@code ArithmeticIndexSequence} with negated offset and scale.
	 */
	@Override
	public IndexSequence minus() {
		return new ArithmeticIndexSequence(-offset, -scale, granularity, mod, len);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>If the sequence has no offset, unit scale, and the current modulus is divisible
	 * by the new modulus, returns a new {@code ArithmeticIndexSequence} with adjusted
	 * modulus. Otherwise, falls back to the default element-wise modulo operation.
	 */
	@Override
	public IndexSequence mod(long m) {
		if (offset != 0 || scale != 1 || mod % m != 0) {
			return IndexSequence.super.mod(m);
		}

		return new ArithmeticIndexSequence(0, 1, granularity, granularity * m, len);
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
	 * <p>An arithmetic sequence is constant only if its length is 1.
	 */
	@Override
	public boolean isConstant() {
		return lengthLong() == 1;
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
	 * <p>When {@link #enableAutoExpression} is {@code true}, generates an optimized
	 * expression using the arithmetic formula directly:
	 * <pre>{@code
	 * ((index % mod) / granularity) * |scale| [negated if scale < 0] + offset
	 * }</pre>
	 *
	 * <p>This is more efficient than pattern detection as the formula parameters
	 * are already known.
	 */
	@Override
	public Expression getExpression(Expression index, boolean isInt) {
		if (!enableAutoExpression) return IndexSequence.super.getExpression(index, isInt);

		Expression pos = index.imod(mod).divide(e(granularity));
		Expression r = pos.multiply(e(Math.abs(scale)));
		if (scale < 0) r = r.minus();
		if (offset != 0) r = r.add(e(offset));

//		Expression exp = IndexSequence.super.getExpression(index, isInt);
//		if (!r.equals(exp)) {
//			IndexSequence.super.getExpression(index, isInt);
//		}

		return r;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a stream of the distinct values in one cycle of the sequence
	 * (up to the modulus). If the offset is non-zero, falls back to the default
	 * implementation.
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
	 * the same offset, scale, granularity, modulus, and length.
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
}
