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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mask;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.profile.OperationMetadata;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

/**
 * Recognises the closed forms a sequence of kernel-index values can be replaced by,
 * consuming the sequence incrementally.
 *
 * <p>Three forms are recognised, tried in this order once the whole sequence has
 * been seen:</p>
 * <ul>
 *   <li><b>constant</b> — every value is the same;</li>
 *   <li><b>mask</b> — the values are {@code 0} and one other integer {@code v}, and
 *       {@code v} occupies exactly one position or one contiguous run, emitted as a
 *       {@link Mask} over an index comparison;</li>
 *   <li><b>arithmetic progression</b> — {@code value(i) = initial + ((i % period) / granularity) * delta},
 *       where the granularity is the number of consecutive positions sharing a value and
 *       the period, if any, is the position at which the progression restarts.</li>
 * </ul>
 *
 * <p>Every one of these forms can be <em>refuted</em> long before the sequence ends: a
 * third distinct value rules out the constant and the mask, a second wrap rules out the
 * progression. The matcher tracks each form's viability as values arrive, and
 * {@link #isPossible()} turns {@code false} at the first position where no form remains,
 * so a caller enumerating an expensive expression can stop there. Confirming a form
 * always requires the complete sequence, because every position is verified against
 * the emitted expression's prediction.</p>
 *
 * <p>Values are compared as {@code double}. Sequences of integer-typed expressions are
 * exact in this representation (see {@link IndexRange#exact(long)}).</p>
 *
 * @see IndexSequence#getExpression(Expression, boolean)
 * @see Expression#matchSeries(Index, long, long)
 * @see #consume(Expression, Index)
 * @see KernelSeriesProvider
 */
public class KernelSeriesMatcher implements ExpressionFeatures {
	/** The number of values the complete sequence has. */
	private final long length;

	/** The number of values consumed so far. */
	private long position;

	/** The value at position 0. */
	private double first;

	/** The distinct values seen so far, in encounter order, up to the third. */
	private final double[] distinct;

	/** The number of distinct values seen so far, saturating at 3. */
	private int distinctCount;

	/** Whether the mask form is still viable. */
	private boolean maskPossible;

	/** Position of the first non-zero value, or -1 if none has been seen. */
	private long maskStart;

	/** Position of the first zero after {@link #maskStart}, or -1 if the run has not ended. */
	private long maskEnd;

	/** Whether the arithmetic progression form is still viable. */
	private boolean arithmeticPossible;

	/** Number of consecutive positions sharing a value, or -1 until the first change. */
	private long granularity;

	/** The difference between consecutive distinct values of the progression. */
	private double delta;

	/** Position at which the progression restarts, or -1 if it has not wrapped. */
	private long period;

	/**
	 * Creates a matcher for a sequence of the given length.
	 *
	 * @param length the number of values the sequence will have; must be positive
	 * @throws IllegalArgumentException if {@code length} is not positive
	 */
	public KernelSeriesMatcher(long length) {
		if (length <= 0) {
			throw new IllegalArgumentException("Sequence length must be positive");
		}

		this.length = length;
		this.distinct = new double[3];
		reset();
	}

	/**
	 * Returns the number of values the complete sequence has.
	 *
	 * @return the sequence length
	 */
	public long getLength() { return length; }

	/**
	 * Returns the number of values consumed so far.
	 *
	 * @return the current position
	 */
	public long getPosition() { return position; }

	/**
	 * Returns {@code true} once every value of the sequence has been consumed.
	 *
	 * @return whether the sequence is complete
	 */
	public boolean isComplete() { return position >= length; }

	/**
	 * Returns {@code true} while at least one recognised form is still consistent
	 * with the values seen so far. Once this is {@code false}, consuming further
	 * values cannot change the outcome and {@link #getExpression} will return
	 * {@code null}.
	 *
	 * @return whether any form remains viable
	 */
	public boolean isPossible() {
		return distinctCount < 2 || maskPossible || arithmeticPossible;
	}

	/**
	 * Returns {@code true} if the arithmetic progression restarted part way through
	 * the sequence, so the emitted expression includes a modulus.
	 *
	 * @return whether a period was detected
	 */
	public boolean isModular() { return period > 0; }

	/**
	 * Consumes the first {@code count} values of the given block.
	 *
	 * <p>Values beyond the sequence length are ignored, as are values arriving after
	 * every form has been refuted.</p>
	 *
	 * @param values the block of values
	 * @param count  the number of leading positions of {@code values} to consume
	 */
	public void accept(double[] values, int count) {
		for (int i = 0; i < count && position < length && isPossible(); i++) {
			accept(values[i]);
		}
	}

	/**
	 * Consumes the values of an expression over the first {@link #getLength()} positions
	 * of an index, producing them block by block and stopping at the first block after
	 * which no recognisable form remains.
	 *
	 * <p>The first block is evaluated alone, so an irregular expression costs a single
	 * block; later blocks are evaluated in parallel rounds of doubling size, up to the
	 * common pool's parallelism, so a regular expression is enumerated at full speed.
	 * Should block evaluation refuse an integer intermediate as inexact, the values are
	 * recomputed point by point via {@link Expression#value(IndexValues)}.</p>
	 *
	 * @param exp   the expression to enumerate
	 * @param index the index to vary
	 * @return this matcher, complete or refuted
	 * @throws IllegalStateException if values have already been consumed
	 */
	public KernelSeriesMatcher consume(Expression<?> exp, Index index) {
		if (position != 0) {
			throw new IllegalStateException("Values have already been consumed");
		}

		try {
			List<IndexRange> blocks = IndexRange.partition(index, Math.toIntExact(length));
			int parallelism = Math.max(1, ForkJoinPool.getCommonPoolParallelism());
			int round = 1;

			int b = 0;

			while (b < blocks.size() && isPossible()) {
				List<IndexRange> current = blocks.subList(b, Math.min(b + round, blocks.size()));
				Stream<IndexRange> ranges = current.size() > 1 ? current.parallelStream() : current.stream();
				double[][] values = ranges.map(exp::values).toArray(double[][]::new);

				for (int k = 0; k < values.length && isPossible(); k++) {
					accept(values[k], current.get(k).getLength());
				}

				b += current.size();
				round = Math.min(round << 1, parallelism);
			}

			if (isPossible() && !isComplete()) {
				throw new IllegalStateException("Consumed " + position + " of " + length + " values");
			}
		} catch (IndexRange.InexactValueException e) {
			reset();

			for (int i = 0; i < length && isPossible(); i++) {
				accept(exp.value(new IndexValues().put(index, i)).doubleValue());
			}
		}

		return this;
	}

	/**
	 * Returns this matcher to its initial state.
	 */
	private void reset() {
		position = 0;
		distinctCount = 0;
		maskPossible = true;
		maskStart = -1;
		maskEnd = -1;
		arithmeticPossible = true;
		granularity = -1;
		period = -1;
	}

	/**
	 * Consumes the next value of the sequence.
	 *
	 * @param v the value at the current position
	 * @throws IllegalStateException if the sequence is already complete
	 */
	public void accept(double v) {
		if (position >= length) {
			throw new IllegalStateException("Sequence of length " + length + " is complete");
		}

		long p = position++;

		if (p == 0) {
			first = v;
			distinct[0] = v;
			distinctCount = 1;
			maskPossible = v == 0.0;
			return;
		}

		acceptDistinct(v);
		if (maskPossible) acceptMask(v, p);
		if (arithmeticPossible) acceptArithmetic(v, p);
	}

	/**
	 * Tracks the distinct values seen, refuting the mask once a third appears or a
	 * non-integral value is involved.
	 */
	private void acceptDistinct(double v) {
		if (distinctCount >= 3) return;

		for (int i = 0; i < distinctCount; i++) {
			if (distinct[i] == v) return;
		}

		distinct[distinctCount++] = v;

		if (distinctCount > 2 || v != Math.rint(v)) {
			maskPossible = false;
		}
	}

	/**
	 * Tracks the run of the non-zero value, refuting the mask when a second run begins.
	 */
	private void acceptMask(double v, long p) {
		if (v != 0.0) {
			if (maskStart < 0) {
				maskStart = p;
			} else if (maskEnd >= 0) {
				maskPossible = false;
			}
		} else if (maskStart >= 0 && maskEnd < 0) {
			maskEnd = p;
		}
	}

	/**
	 * Verifies the value against the arithmetic progression, establishing the
	 * granularity at the first change and the period at the first wrap.
	 */
	private void acceptArithmetic(double v, long p) {
		if (granularity < 0) {
			if (v != first) {
				granularity = p;
				delta = v - first;
			}

			return;
		}

		if (predict(p) == v) return;

		if (period < 0 && p % granularity == 0 && v == first) {
			period = p;
		} else {
			arithmeticPossible = false;
		}
	}

	/**
	 * Returns the progression's value at the given position.
	 */
	private double predict(long p) {
		long step = (period < 0 ? p : p % period) / granularity;
		return first + step * delta;
	}

	/**
	 * Returns an expression producing the recognised form of the complete sequence,
	 * or {@code null} if the sequence matches none of the recognised forms.
	 *
	 * @param index the index expression the sequence was enumerated over
	 * @param isInt {@code true} to emit integer constants, {@code false} for floating point
	 * @return the matched expression, or {@code null}
	 * @throws IllegalStateException if the sequence is neither complete nor already refuted
	 */
	public Expression getExpression(Expression index, boolean isInt) {
		if (!isPossible()) return null;

		if (!isComplete()) {
			throw new IllegalStateException("Only " + position + " of " + length + " values have been seen");
		}

		long start = System.nanoTime();

		try {
			if (distinctCount == 1) {
				return isInt ? e((long) first) : new DoubleConstant(first);
			}

			if (maskPossible && distinctCount == 2) {
				Expression<?> mask = maskExpression(index, isInt);
				if (mask != null) return mask;
			}

			if (arithmeticPossible) {
				return arithmeticExpression(index, isInt);
			}

			return null;
		} finally {
			IndexSequence.timing.addEntry(isInt ? "int" : "fp", System.nanoTime() - start);
		}
	}

	/**
	 * Builds the {@link Mask} for a sequence of zeros with a single run of one value,
	 * or returns {@code null} if the run is not contiguous.
	 */
	private Expression<?> maskExpression(Expression index, boolean isInt) {
		long total = (maskEnd < 0 ? length : maskEnd) - maskStart;
		Expression<Boolean> condition;

		if (total == 1) {
			condition = index.eq(new IntegerConstant(Math.toIntExact(maskStart)));
		} else {
			condition = index.greaterThanOrEqual(new IntegerConstant(Math.toIntExact(maskStart)))
					.and(index.lessThan(new IntegerConstant(Math.toIntExact(maskStart + total))));
		}

		Expression<?> value = isInt ? e((long) distinct[1]) : new DoubleConstant(distinct[1]);
		return Mask.of(condition, (Expression) value);
	}

	/**
	 * Builds the expression for the verified arithmetic progression.
	 */
	private Expression<?> arithmeticExpression(Expression index, boolean isInt) {
		Expression<?> r = index;

		if (period > 0) {
			r = r.imod(period);
		}

		if (granularity > 1) {
			r = r.toInt().divide(new IntegerConstant(Math.toIntExact(granularity)));
		}

		if (isInt) {
			if (delta != 1.0) r = r.multiply(e((long) delta));
			if (first != 0.0) r = r.add(e((long) first));
		} else {
			if (delta != 1.0) r = r.multiply(new DoubleConstant(delta));
			if (first != 0.0) r = r.add(new DoubleConstant(first));
		}

		return r;
	}

	/**
	 * Creates a default kernel series provider with no metadata and no maximum length.
	 *
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider() {
		return defaultProvider(null);
	}

	/**
	 * Creates a default kernel series provider with the given metadata and no maximum length.
	 *
	 * @param metadata the operation metadata to associate with the provider
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider(OperationMetadata metadata) {
		return defaultProvider(metadata, OptionalInt.empty());
	}

	/**
	 * Creates a default kernel series provider with no metadata and the given maximum length.
	 *
	 * @param count the maximum number of elements to process
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider(int count) {
		return defaultProvider(null, count);
	}

	/**
	 * Creates a default kernel series provider with the given metadata and maximum length.
	 *
	 * @param metadata the operation metadata to associate with the provider
	 * @param count the maximum number of elements to process
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider(OperationMetadata metadata, int count) {
		return defaultProvider(metadata, OptionalInt.of(count));
	}

	/**
	 * Creates a default kernel series provider with the given metadata and optional maximum length.
	 *
	 * <p>The returned provider recognises the forms described by this class and stores
	 * nothing: a sequence that matches no form is left as the original expression.</p>
	 *
	 * @param metadata the operation metadata to associate with the provider
	 * @param count the maximum number of elements to process, or empty for no limit
	 * @return a default kernel series provider
	 */
	public static KernelSeriesProvider defaultProvider(OperationMetadata metadata, OptionalInt count) {
		return new KernelSeriesProvider() {
			@Override
			public OperationMetadata getMetadata() {
				return metadata;
			}

			@Override
			public OptionalInt getMaximumLength() {
				return count;
			}
		};
	}
}
