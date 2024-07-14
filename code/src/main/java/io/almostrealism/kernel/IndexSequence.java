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

public interface IndexSequence extends Sequence<Number>, ConsoleFeatures {
	boolean enableGranularityDetection = true;
	boolean enableModValidation = false;
	TimingMetric timing = Scope.console.timing("kernelSeriesMatcher");

	default IndexSequence map(UnaryOperator<Number> op) {
		return ArrayIndexSequence.of(getType(), values().map(op).toArray(Number[]::new), lengthLong());
	}

	default IndexSequence mapInt(IntUnaryOperator op) {
		return map(n -> Integer.valueOf(op.applyAsInt(n.intValue())));
	}

	default IndexSequence mapLong(LongUnaryOperator op) {
		return map(n -> Long.valueOf(op.applyAsLong(n.longValue())));

	}

	default IndexSequence mapDouble(DoubleUnaryOperator op) {
		return map(n -> Double.valueOf(op.applyAsDouble(n.doubleValue())));
	}

	default IndexSequence multiply(long operand) {
		return mapLong(n -> n * operand);
	}

	default IndexSequence divide(long operand) {
		return mapLong(n -> n / operand);
	}

	default IndexSequence minus() {
		return map(n -> -n.doubleValue());
	}

	default IndexSequence mod(int m) {
		if (m < 0)
			throw new IllegalArgumentException();
		if (m > max() && m > Math.abs(min())) return this;

		return mapInt(i -> i % m);
	}

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
			return ArrayIndexSequence.of(getType(), IntStream.range(0, getMod())
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

	IndexSequence subset(long len);

	default boolean congruent(IndexSequence other) {
		if (equals(other)) return true;

		IndexSequence comp = eq(other);
		if (!comp.isConstant() || comp.valueAt(0).intValue() != 1) return false;
		return true;
	}

	default IntStream intStream() {
		return stream().mapToInt(Number::intValue);
	}

	default LongStream longStream() {
		return stream().mapToLong(Number::longValue);
	}

	default DoubleStream doubleStream() {
		return stream().mapToDouble(Number::doubleValue);
	}

	default IntStream intValues() {
		return values().mapToInt(Number::intValue);
	}

	default LongStream longValues() {
		return values().mapToLong(Number::longValue);
	}

	default DoubleStream doubleValues() {
		return values().mapToDouble(Number::doubleValue);
	}

	default LongStream matchingIndices(double value) {
		return LongStream.range(0, lengthLong()).filter(i -> valueAt(i).doubleValue() == value);
	}

	@Override
	default Number[] distinct() {
		return values().distinct().toArray(Number[]::new);
	}

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

	default long max() {
		if (isConstant()) return valueAt(0).longValue();

		if (valueAt(0) instanceof Integer) {
			return intValues().max().orElseThrow();
		} else if (valueAt(0) instanceof Long) {
			return longValues().max().orElseThrow();
		} else {
			return (long) Math.ceil(doubleValues().max().orElseThrow());
		}
	}

	boolean isConstant();

	int getGranularity();

	int getMod();

	default Expression<? extends Number> getExpression(Index index) {
		if (index instanceof Expression) {
			return getExpression((Expression) index, !((Expression) index).isFP());
		} else {
			throw new UnsupportedOperationException();
		}
	}

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
			int m = getMod();
			int end = m;
			i: for (int i = 2 * granularity; i < m; i += granularity) {
				double actual = doubleAt(i);
				double prediction = doubleAt(i - 1) + delta;

				if (end == m && prediction != actual) {
					end = i;
				}

				if (prediction % end != actual) {
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

	@Override
	default Console console() {
		return Scope.console;
	}

	static boolean fractionalValue(Number[] distinct) {
		for (Number n : distinct) {
			double d = Math.abs(n.doubleValue() - n.intValue());
			if (d > 0) return true;
		}

		return false;
	}
}
