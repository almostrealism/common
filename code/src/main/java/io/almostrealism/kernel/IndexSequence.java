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

import io.almostrealism.collect.IndexSet;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.LongConstant;
import io.almostrealism.expression.Mask;
import io.almostrealism.scope.Scope;
import io.almostrealism.util.Sequence;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.TimingMetric;

import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.LongUnaryOperator;
import java.util.function.UnaryOperator;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * A {@link Sequence} of {@link Number}s which represent index values used in kernel computations.
 *
 * <p>{@code IndexSequence} is a fundamental building block for kernel series analysis and optimization.
 * It represents a sequence of numeric index values that can be analyzed for patterns (arithmetic,
 * constant, etc.) and converted into efficient {@link Expression} representations for code generation.
 *
 * <h2>Core Capabilities</h2>
 * <ul>
 *   <li><b>Pattern Detection</b>: Identifies arithmetic sequences, constant sequences, and other
 *       patterns that can be expressed as simple mathematical expressions</li>
 *   <li><b>Transformation Operations</b>: Supports map, multiply, divide, mod, and other operations
 *       that preserve or transform sequence patterns</li>
 *   <li><b>Expression Generation</b>: Converts detected patterns into {@link Expression} trees
 *       for efficient code generation via {@link #getExpression(Index)}</li>
 *   <li><b>Granularity and Modulus</b>: Tracks repeating patterns via granularity (how many
 *       consecutive values are identical) and modulus (cycle length)</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link ArrayIndexSequence}: Stores values explicitly in an array; general-purpose</li>
 *   <li>{@link ArithmeticIndexSequence}: Efficient representation for arithmetic sequences
 *       (sequences of the form {@code offset + scale * (pos / granularity) % mod})</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a simple index sequence [0, 1, 2, 3, ...]
 * IndexSequence seq = ArrayIndexSequence.of(new int[]{0, 1, 2, 3, 4, 5});
 *
 * // Apply transformations
 * IndexSequence doubled = seq.multiply(2);  // [0, 2, 4, 6, 8, 10]
 * IndexSequence offset = doubled.mapInt(i -> i + 10);  // [10, 12, 14, 16, 18, 20]
 *
 * // Convert to expression for code generation
 * Expression<?> expr = seq.getExpression(kernelIndex, true);
 * }</pre>
 *
 * <h2>Pattern Detection</h2>
 * <p>The {@link #getExpression(Expression, boolean)} method analyzes the sequence to detect:
 * <ul>
 *   <li><b>Constant sequences</b>: Returns an {@link IntegerConstant} or {@link DoubleConstant}</li>
 *   <li><b>Binary patterns</b>: Sequences with only two distinct values (e.g., [0,0,1,0,0,1])
 *       are converted to conditional {@link Mask} expressions</li>
 *   <li><b>Arithmetic sequences</b>: Sequences where values follow a linear pattern are
 *       converted to expressions like {@code index * delta + initial}</li>
 * </ul>
 *
 * @author Michael Murray
 * @see Sequence
 * @see ArrayIndexSequence
 * @see ArithmeticIndexSequence
 * @see SequenceGenerator
 */
public interface IndexSequence extends Sequence<Number>, IndexSet, ConsoleFeatures {

	/**
	 * Flag to enable automatic granularity detection in pattern analysis.
	 * When {@code true}, the {@link #getExpression} method will attempt to detect
	 * the granularity (number of consecutive identical values) in the sequence.
	 */
	boolean enableGranularityDetection = true;

	/**
	 * Flag to enable validation of modulus-based optimizations.
	 * When {@code true}, expression replacements using modulus operations
	 * will be validated by comparing the generated sequence against the original.
	 */
	boolean enableModValidation = false;

	/**
	 * Timing metric for tracking kernel series matching performance.
	 */
	TimingMetric timing = Scope.console.timing("kernelSeriesMatcher");

	/**
	 * Applies a transformation function to each value in this sequence.
	 *
	 * @param op the transformation to apply to each value
	 * @return a new {@code IndexSequence} with the transformed values
	 */
	default IndexSequence map(UnaryOperator<Number> op) {
		return ArrayIndexSequence.of(getType(), values().map(op).toArray(Number[]::new), lengthLong());
	}

	/**
	 * Applies an integer transformation function to each value in this sequence.
	 *
	 * @param op the integer transformation to apply to each value
	 * @return a new {@code IndexSequence} with the transformed values
	 */
	default IndexSequence mapInt(IntUnaryOperator op) {
		return map(n -> Integer.valueOf(op.applyAsInt(n.intValue())));
	}

	/**
	 * Applies a long transformation function to each value in this sequence.
	 *
	 * @param op the long transformation to apply to each value
	 * @return a new {@code IndexSequence} with the transformed values
	 */
	default IndexSequence mapLong(LongUnaryOperator op) {
		return map(n -> Long.valueOf(op.applyAsLong(n.longValue())));

	}

	/**
	 * Applies a double transformation function to each value in this sequence.
	 *
	 * @param op the double transformation to apply to each value
	 * @return a new {@code IndexSequence} with the transformed values
	 */
	default IndexSequence mapDouble(DoubleUnaryOperator op) {
		return map(n -> Double.valueOf(op.applyAsDouble(n.doubleValue())));
	}

	/**
	 * Multiplies each value in this sequence by the given operand.
	 *
	 * @param operand the value to multiply by
	 * @return a new {@code IndexSequence} with each value multiplied
	 */
	default IndexSequence multiply(long operand) {
		return mapLong(n -> n * operand);
	}

	/**
	 * Divides each value in this sequence by the given operand using integer division.
	 *
	 * @param operand the divisor
	 * @return a new {@code IndexSequence} with each value divided
	 */
	default IndexSequence divide(long operand) {
		return mapLong(n -> n / operand);
	}

	/**
	 * Negates each value in this sequence.
	 *
	 * @return a new {@code IndexSequence} with each value negated
	 */
	default IndexSequence minus() {
		return map(n -> -n.doubleValue());
	}

	/**
	 * Applies modulo operation to each value in this sequence.
	 *
	 * <p>If the modulus is greater than the maximum absolute value in the sequence,
	 * this method returns the sequence unchanged as an optimization.
	 *
	 * @param m the modulus (must be non-negative)
	 * @return a new {@code IndexSequence} with each value reduced modulo {@code m}
	 * @throws IllegalArgumentException if {@code m} is negative
	 */
	default IndexSequence mod(long m) {
		if (m < 0)
			throw new IllegalArgumentException();
		if (m > max() && m > Math.abs(min())) return this;

		if (m <= Integer.MAX_VALUE && m >= Integer.MIN_VALUE &&
				max() <= Integer.MAX_VALUE && min() >= Integer.MIN_VALUE) {
			return mapInt(i -> i % (int) m);
		} else {
			return mapLong(i -> i % m);
		}
	}

	/**
	 * Compares this sequence with another for element-wise equality.
	 *
	 * <p>Returns a new sequence where each position contains 1 if the values
	 * at that position are equal, and 0 otherwise.
	 *
	 * @param other the sequence to compare with (must have the same length)
	 * @return a new {@code IndexSequence} containing 1s and 0s indicating equality,
	 *         or {@code null} if the comparison cannot be performed efficiently
	 * @throws IllegalArgumentException if the sequences have different lengths
	 */
	default IndexSequence eq(IndexSequence other) {
		if (lengthLong() != other.lengthLong()) throw new IllegalArgumentException();

		if (isConstant() && other.isConstant()) {
			return ArrayIndexSequence.of(valueAt(0).doubleValue() == other.valueAt(0).doubleValue() ?
					Integer.valueOf(1) : Integer.valueOf(0), lengthLong());
		}

		if (getGranularity() != other.getGranularity() && lengthLong() > Integer.MAX_VALUE) {
			return null;
		}

		if (getMod() == other.getMod()) {
			return ArrayIndexSequence.of(getType(), LongStream.range(0, getMod())
					.parallel()
					.mapToObj(i -> valueAt(i).doubleValue() == other.valueAt(i).doubleValue() ?
							Integer.valueOf(1) : Integer.valueOf(0))
					.toArray(Number[]::new), lengthLong());
		}

		return ArrayIndexSequence.of(getType(), IntStream.range(0, length())
				.parallel()
				.mapToObj(i -> valueAt(i).doubleValue() == other.valueAt(i).doubleValue() ?
						Integer.valueOf(1) : Integer.valueOf(0))
				.toArray(Number[]::new));
	}

	/**
	 * Returns a subset of this sequence containing only the first {@code len} elements.
	 *
	 * @param len the number of elements to include in the subset
	 * @return a new {@code IndexSequence} containing the first {@code len} elements
	 */
	IndexSequence subset(long len);

	/**
	 * {@inheritDoc}
	 *
	 * <p>This operation is not currently implemented for index sequences.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		// TODO
		throw new UnsupportedOperationException();
	}

	/**
	 * Tests whether this sequence is congruent (element-wise equal) to another sequence.
	 *
	 * @param other the sequence to compare with
	 * @return {@code true} if all corresponding elements are equal, {@code false} otherwise
	 */
	default boolean congruent(IndexSequence other) {
		if (equals(other)) return true;

		IndexSequence comp = eq(other);
		if (!comp.isConstant() || comp.valueAt(0).intValue() != 1) return false;
		return true;
	}

	/**
	 * Returns the values of this sequence as an {@code IntStream}.
	 *
	 * @return an {@code IntStream} of all values in this sequence
	 */
	default IntStream intStream() {
		return stream().mapToInt(Number::intValue);
	}

	/**
	 * Returns the values of this sequence as a {@code LongStream}.
	 *
	 * @return a {@code LongStream} of all values in this sequence
	 */
	default LongStream longStream() {
		return stream().mapToLong(Number::longValue);
	}

	/**
	 * Returns the values of this sequence as a {@code DoubleStream}.
	 *
	 * @return a {@code DoubleStream} of all values in this sequence
	 */
	default DoubleStream doubleStream() {
		return stream().mapToDouble(Number::doubleValue);
	}

	/**
	 * Returns the distinct values of this sequence as an {@code IntStream}.
	 *
	 * @return an {@code IntStream} of distinct values (up to the modulus)
	 */
	default IntStream intValues() {
		return values().mapToInt(Number::intValue);
	}

	/**
	 * Returns the distinct values of this sequence as a {@code LongStream}.
	 *
	 * @return a {@code LongStream} of distinct values (up to the modulus)
	 */
	default LongStream longValues() {
		return values().mapToLong(Number::longValue);
	}

	/**
	 * Returns the distinct values of this sequence as a {@code DoubleStream}.
	 *
	 * @return a {@code DoubleStream} of distinct values (up to the modulus)
	 */
	default DoubleStream doubleValues() {
		return values().mapToDouble(Number::doubleValue);
	}

	/**
	 * Returns the indices at which this sequence contains the specified value.
	 *
	 * @param value the value to search for
	 * @return a {@code LongStream} of indices where the sequence equals {@code value}
	 */
	default LongStream matchingIndices(double value) {
		return LongStream.range(0, lengthLong()).filter(i -> valueAt(i).doubleValue() == value);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return an array of all distinct values in this sequence
	 */
	@Override
	default Number[] distinct() {
		return values().distinct().toArray(Number[]::new);
	}

	/**
	 * Returns the minimum value in this sequence.
	 *
	 * @return the minimum value as a {@code long}
	 */
	default long min() {
		if (isConstant()) return valueAt(0).longValue();

		if (valueAt(0) instanceof Integer) {
			return intValues().min().orElseThrow();
		} else if (valueAt(0) instanceof Long) {
			return longValues().min().orElseThrow();
		} else {
			return (long) Math.ceil(doubleValues().min().orElseThrow());
		}
	}

	/**
	 * Returns the maximum value in this sequence.
	 *
	 * @return the maximum value as a {@code long}
	 */
	default long max() {
		if (isConstant()) return valueAt(0).longValue();

		if (valueAt(0) instanceof Double) {
			return (long) Math.ceil(doubleValues().max().orElseThrow());
		} else {
			return longValues().max().orElseThrow();
		}
	}

	/**
	 * Returns whether this sequence consists of a single constant value.
	 *
	 * <p>A constant sequence returns the same value for all index positions.
	 * This is useful for optimization, as constant sequences can be replaced
	 * with simple constant expressions during code generation.
	 *
	 * @return {@code true} if all values in this sequence are identical
	 */
	boolean isConstant();

	/**
	 * Returns the granularity of this sequence.
	 *
	 * <p>The granularity indicates how many consecutive index positions share
	 * the same value. For example, a sequence [0,0,1,1,2,2] has granularity 2.
	 * This is used to optimize code generation by detecting repeating patterns.
	 *
	 * @return the number of consecutive identical values, or 1 if values change at every position
	 */
	int getGranularity();

	/**
	 * Returns the modulus (cycle length) of this sequence.
	 *
	 * <p>The modulus indicates the period after which the sequence repeats.
	 * For a sequence of length 12 that repeats every 4 elements (e.g., [0,1,2,3,0,1,2,3,0,1,2,3]),
	 * the modulus would be 4. This allows efficient representation of repeating sequences.
	 *
	 * @return the cycle length of the sequence
	 */
	long getMod();

	/**
	 * Generates an {@link Expression} that computes the values of this sequence.
	 *
	 * <p>This method analyzes the sequence to detect patterns and generates
	 * an optimized expression. The result can be used in generated code to
	 * compute sequence values at runtime.
	 *
	 * @param index the index expression to use as the input variable
	 * @return an expression that computes the sequence value for any given index,
	 *         or throws if the index is not an {@link Expression}
	 * @throws UnsupportedOperationException if the index is not an Expression
	 * @see #getExpression(Expression, boolean)
	 */
	default Expression<? extends Number> getExpression(Index index) {
		if (index instanceof Expression) {
			return getExpression((Expression) index, !((Expression) index).isFP());
		} else {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Generates an {@link Expression} that computes the values of this sequence.
	 *
	 * <p>This is the core method for converting index sequences into expressions.
	 * It performs pattern detection and generates optimized expressions based on
	 * the detected pattern type:
	 * <ul>
	 *   <li><b>Constant sequences</b>: Returns an {@link IntegerConstant} or {@link DoubleConstant}</li>
	 *   <li><b>Binary sequences</b>: Sequences with only two distinct values are converted
	 *       to conditional {@link Mask} expressions</li>
	 *   <li><b>Arithmetic sequences</b>: Sequences where {@code value[i] = initial + delta * i}
	 *       are converted to expressions like {@code index * delta + initial}</li>
	 * </ul>
	 *
	 * @param index the index expression to use as the input variable
	 * @param isInt whether to generate integer expressions (vs floating-point)
	 * @return an expression that computes the sequence value, or {@code null}
	 *         if no pattern is detected and no expression can be generated
	 */
	default Expression getExpression(Expression index, boolean isInt) {
		long start = System.nanoTime();

		try {
			if (isConstant()) {
				return isInt ? new IntegerConstant(intAt(0)) : new DoubleConstant(doubleAt(0));
			}

			Number distinct[] = distinct();
			if (distinct.length == 1) {
				warn("Constant sequence not detected by IndexSequence");
				return isInt ? new IntegerConstant((int) distinct[0]) : new DoubleConstant(distinct[0].doubleValue());
			}

			if (distinct.length == 2 && distinct[0].intValue() == 0 && !fractionalValue(distinct)) {
				int first = (int) matchingIndices(distinct[1].intValue())
						.filter(i -> i < Integer.MAX_VALUE)
						.findFirst().orElse(-1);
				if (first < 0)
					throw new UnsupportedOperationException();

				int tot = doubleStream().mapToInt(v -> v == distinct[1].intValue() ? 1 : 0).sum();

				long cont = doubleStream().skip(first).limit(tot).distinct().count();

				Expression<Boolean> condition = null;

				if (tot == 1) {
					condition = index.eq(new IntegerConstant(first));
				} else if (cont == 1) {
					condition =
							index.greaterThanOrEqual(new IntegerConstant(first)).and(
									index.lessThan(new IntegerConstant(first + tot)));
				}

				if (condition != null) {
					if (isInt) {
						if (distinct[1].longValue() < Integer.MAX_VALUE && distinct[1].longValue() > Integer.MIN_VALUE) {
							return Mask.of(condition, new IntegerConstant(distinct[1].intValue()));
						} else {
							return Mask.of(condition, new LongConstant(distinct[1].longValue()));
						}
					} else {
						return Mask.of(condition, new DoubleConstant(distinct[1].doubleValue()));
					}
				}
			}

			int granularity = enableGranularityDetection ? getGranularity() : 1;
			if (lengthLong() % granularity != 0) {
				granularity = 1;
			}

			double initial = doubleAt(0);
			double delta = doubleAt(granularity) - doubleAt(0);
			boolean isArithmetic = true;
			long m = getMod();
			long end = m;
			i: for (int i = 2 * granularity; i < m; i += granularity) {
				double actual = doubleAt(i);
				double prediction = arithmeticSequenceValue(i, end, granularity, initial, delta);

				if (end == m && prediction != actual) {
					end = i;
					prediction = arithmeticSequenceValue(i, end, granularity, initial, delta);
				}

				if (prediction != actual) {
					isArithmetic = false;
					break i;
				}
			}

			if (isArithmetic) {
				Expression<?> r = index;

				if (end != lengthLong()) {
					r = r.imod(end);
				}

				if (granularity > 1) {
					r = r.toInt().divide(new IntegerConstant(granularity));
				}

				if (isInt) {
					if (delta != 1.0) r = r.multiply(new IntegerConstant((int) delta));
					if (initial != 0.0) r = r.add(new IntegerConstant((int) initial));
				} else {
					if (delta != 1.0) r = r.multiply(new DoubleConstant(delta));
					if (initial != 0.0) r = r.add(new DoubleConstant(initial));
				}

				if (enableModValidation && end != lengthLong()) {
					IndexSequence newSeq = r.sequence((Index) index, lengthLong());

					if (!newSeq.congruent(this)) {
						r.sequence((Index) index, lengthLong());
						throw new RuntimeException();
					} else {
						warn("Sequence replacement using mod is experimental");
					}
				}

				return r;
			}

			return null;
		} finally {
			timing.addEntry(isInt ? "int" : "fp", System.nanoTime() - start);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the console for logging warnings and messages
	 */
	@Override
	default Console console() {
		return Scope.console;
	}

	/**
	 * Computes the value of an arithmetic sequence at the given index position.
	 *
	 * <p>The arithmetic sequence is defined by the formula:
	 * <pre>{@code
	 * value = initial + ((index % mod) / granularity) * delta
	 * }</pre>
	 *
	 * @param index the index position
	 * @param mod the modulus (cycle length)
	 * @param granularity the number of consecutive identical values
	 * @param initial the initial value (value at index 0)
	 * @param delta the difference between consecutive distinct values
	 * @return the computed value at the given index
	 */
	static double arithmeticSequenceValue(int index, long mod, int granularity,
										  double initial, double delta) {
		long position = (index % mod) / granularity;
		return initial + position * delta;
	}

	/**
	 * Determines whether any of the given values has a fractional component.
	 *
	 * <p>This is used to decide whether to generate integer or floating-point
	 * expressions for sequence values.
	 *
	 * @param distinct the array of values to check
	 * @return {@code true} if any value has a non-zero fractional part
	 */
	static boolean fractionalValue(Number[] distinct) {
		for (Number n : distinct) {
			double d = Math.abs(n.doubleValue() - n.intValue());
			if (d > 0) return true;
		}

		return false;
	}
}
