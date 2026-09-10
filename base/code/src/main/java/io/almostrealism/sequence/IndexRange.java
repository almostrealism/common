/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.expression.Expression;
import io.almostrealism.scope.ScopeSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A contiguous block of values for a single {@link Index}, over which an
 * {@link Expression} is evaluated all at once.
 *
 * <p>{@link IndexValues} describes one point of the index space and
 * {@link Expression#value(IndexValues)} evaluates an expression there. An
 * {@code IndexRange} describes {@link #getLength()} consecutive points starting at
 * {@link #getStart()}, and {@link Expression#values(IndexRange)} evaluates an
 * expression at every one of them into a primitive {@code double[]}. This is how
 * sequences of index-arithmetic values are produced for kernel series detection:
 * every node of the expression tree is visited once per block, with a tight loop
 * over the block inside the node, rather than once per index with the whole tree
 * re-traversed each time.</p>
 *
 * <p>The range memoizes the values of every expression node it evaluates, keyed by
 * node identity. Expression graphs share sub-expressions, and a shared node is
 * therefore evaluated once per block regardless of how many paths reach it. A range
 * is confined to one thread and one block; distinct blocks use distinct ranges.</p>
 *
 * <h2>Numeric representation</h2>
 *
 * <p>All values are stored as {@code double}. Integer-typed expressions compute in
 * exact {@code long} arithmetic and convert their results with {@link #exact(long)},
 * which rejects any magnitude above {@link #MAX_EXACT} (2<sup>53</sup>) rather than
 * silently rounding it. A caller that receives the resulting
 * {@link InexactValueException} falls back to element-wise
 * {@link Expression#value(IndexValues)}, so no result is ever produced from an
 * inexact intermediate.</p>
 *
 * @see IndexValues
 * @see Expression#values(IndexRange)
 * @see Expression#computeValues(IndexRange)
 */
public class IndexRange {
	/** Largest magnitude a {@code long} can have and still be represented exactly as a {@code double}. */
	public static final long MAX_EXACT = 1L << 53;

	/** The index being varied over this range. */
	private final Index index;

	/** The value of the index at position 0 of the range. */
	private final long start;

	/** The number of consecutive index values in the range. */
	private final int length;

	/** Values of every expression node evaluated over this range, by node identity. */
	private final Map<Expression<?>, double[]> memo;

	/** The index values themselves, computed on first request. */
	private double[] positions;

	/** The kernel index values implied by this range, computed on first request. */
	private double[] kernelPositions;

	/**
	 * Creates a range of {@code length} consecutive values of {@code index}
	 * beginning at {@code start}.
	 *
	 * @param index  the index to vary
	 * @param start  the first index value
	 * @param length the number of index values; must be positive
	 * @throws IllegalArgumentException if {@code length} is not positive
	 */
	public IndexRange(Index index, long start, int length) {
		if (length <= 0) {
			throw new IllegalArgumentException("Range length must be positive");
		}

		this.index = index;
		this.start = start;
		this.length = length;
		this.memo = new IdentityHashMap<>();
	}

	/**
	 * Partitions the first {@code len} values of an index into consecutive ranges of at
	 * most {@link ScopeSettings#sequenceBlockSize} values each.
	 *
	 * @param index the index to vary
	 * @param len the number of index values to cover; must be positive
	 * @return the ranges, in order, together covering positions {@code 0} to {@code len - 1}
	 * @throws IllegalArgumentException if {@code len} is not positive
	 */
	public static List<IndexRange> partition(Index index, int len) {
		if (len <= 0) {
			throw new IllegalArgumentException("Sequence length must be positive");
		}

		int size = ScopeSettings.sequenceBlockSize;
		List<IndexRange> ranges = new ArrayList<>((len - 1) / size + 1);

		for (long start = 0; start < len; start += size) {
			ranges.add(new IndexRange(index, start, (int) Math.min(size, len - start)));
		}

		return ranges;
	}

	/**
	 * Returns the index being varied over this range.
	 *
	 * @return the index
	 */
	public Index getIndex() { return index; }

	/**
	 * Returns the value of the index at position 0 of this range.
	 *
	 * @return the first index value
	 */
	public long getStart() { return start; }

	/**
	 * Returns the number of index values in this range.
	 *
	 * @return the range length
	 */
	public int getLength() { return length; }

	/**
	 * Returns the values of {@link #getIndex()} across this range, so that
	 * position {@code i} holds {@code getStart() + i}.
	 *
	 * <p>The array is shared by every caller and must not be modified.</p>
	 *
	 * @return the index values across the range
	 */
	public double[] positions() {
		if (positions == null) {
			double[] p = new double[length];
			for (int i = 0; i < length; i++) {
				p[i] = start + i;
			}

			positions = p;
		}

		return positions;
	}

	/**
	 * Returns the values the kernel index takes across this range.
	 *
	 * <p>Each position is mapped through {@link Index#impliedKernelIndex(long)}, so a
	 * range over a kernel index yields the positions themselves and a range over a
	 * kernel index child yields the kernel index owning each child position, exactly
	 * as {@link IndexValues#put(Index, Integer)} derives it point by point.</p>
	 *
	 * <p>The array is shared by every caller and must not be modified.</p>
	 *
	 * @return the kernel index values across the range
	 * @throws IllegalStateException if the kernel index is not determined by this range
	 */
	public double[] kernelPositions() {
		if (kernelPositions == null) {
			double[] p = new double[length];
			for (int i = 0; i < length; i++) {
				p[i] = index.impliedKernelIndex(start + i).orElseThrow(() ->
						new IllegalStateException("Kernel index is not a value over " + index.getName()));
			}

			kernelPositions = p;
		}

		return kernelPositions;
	}

	/**
	 * Returns {@code true} if the given index is the one varied by this range,
	 * matched by name in the same way {@link IndexValues} resolves named indices.
	 *
	 * @param idx the index to test
	 * @return {@code true} if {@code idx} takes {@link #positions()} over this range
	 */
	public boolean isRangeIndex(Index idx) {
		return index.getName().equals(idx.getName());
	}

	/**
	 * Returns the values a constant takes across this range: a fresh array of the
	 * range's length in which every position holds {@code value}.
	 *
	 * @param value the constant value
	 * @return the constant's values across the range
	 */
	public double[] constant(double value) {
		double[] out = new double[length];
		if (value != 0.0) {
			Arrays.fill(out, value);
		}

		return out;
	}

	/**
	 * Evaluates each of the given expressions across this range.
	 *
	 * @param expressions the expressions to evaluate, typically the children of a node
	 * @return one array of values per expression, in the same order
	 */
	public double[][] values(List<Expression<?>> expressions) {
		double[][] result = new double[expressions.size()][];
		for (int i = 0; i < result.length; i++) {
			result[i] = expressions.get(i).values(this);
		}

		return result;
	}

	/**
	 * Returns the memoized values previously computed for the given node over this
	 * range, or {@code null} if it has not been evaluated yet.
	 *
	 * @param key the expression node (compared by identity)
	 * @return the cached values, or {@code null}
	 */
	public double[] getCachedValues(Expression<?> key) {
		return memo.get(key);
	}

	/**
	 * Memoizes the values computed for the given node over this range.
	 *
	 * @param key    the expression node (compared by identity)
	 * @param values the computed values
	 */
	public void putCachedValues(Expression<?> key, double[] values) {
		memo.put(key, values);
	}

	/**
	 * Converts an exactly computed integer result to its {@code double} representation,
	 * refusing any value that the conversion would round.
	 *
	 * @param value the integer value
	 * @return the same value as a {@code double}
	 * @throws InexactValueException if {@code |value|} exceeds {@link #MAX_EXACT}
	 */
	public static double exact(long value) {
		if (value > MAX_EXACT || value < -MAX_EXACT) {
			throw new InexactValueException(value);
		}

		return value;
	}

	/**
	 * Converts a point-evaluated {@link Number} to its {@code double} representation,
	 * refusing any integer value the conversion would round. A floating-point value is
	 * returned as-is, since it was never exact to begin with.
	 *
	 * @param value the point-evaluated value
	 * @return the same value as a {@code double}
	 * @throws InexactValueException if {@code value} is an integer whose magnitude
	 *         exceeds {@link #MAX_EXACT}
	 */
	public static double exact(Number value) {
		if (value instanceof Double || value instanceof Float) {
			return value.doubleValue();
		}

		return exact(value.longValue());
	}

	/**
	 * Signals that an integer intermediate exceeded the range a {@code double} can
	 * represent exactly, so block evaluation cannot be trusted for the expression.
	 */
	public static class InexactValueException extends ArithmeticException {
		/**
		 * Creates the exception for the offending value.
		 *
		 * @param value the value that could not be represented exactly
		 */
		public InexactValueException(long value) {
			super("Integer value " + value + " cannot be represented exactly as a double");
		}
	}
}
