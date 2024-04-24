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
import java.util.function.UnaryOperator;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class IndexSequence extends ArrayItem<Number> {
	public static boolean enableEqOptimization = false;

	private Base64.Encoder encoder = Base64.getEncoder();
	private Long max;
	private int granularity;

	protected IndexSequence(Class<Number> type, Number[] values, int granularity, long len) {
		super(type, values, len, Number[]::new);
		this.granularity = granularity;
	}

	protected IndexSequence(Number value, long len) {
		super(value, len, Number[]::new);
		this.granularity = 1;
	}

	@Override
	public Number valueAt(int pos) {
		return super.valueAt(pos / granularity);
	}

	public IndexSequence map(UnaryOperator<Number> op) {
		return IndexSequence.of(type, apply(op, Number[]::new), lengthLong());
	}

	public IndexSequence mapInt(IntUnaryOperator op) {
		return IndexSequence.of(Integer.class, apply(v -> op.applyAsInt(v.intValue()), Number[]::new), lengthLong());
	}

	public IndexSequence mapDouble(DoubleUnaryOperator op) {
		return IndexSequence.of(Double.class, apply(v -> op.applyAsDouble(v.doubleValue()), Number[]::new), lengthLong());
	}

	public IndexSequence mod(int m) {
		if (m < 0) throw new IllegalArgumentException();
		if (m > max()) return this;

		return mapInt(i -> i % m);
	}

	public IndexSequence eq(IndexSequence other) {
		if (lengthLong() != other.lengthLong()) throw new IllegalArgumentException();

		if (isConstant() && other.isConstant()) {
			return IndexSequence.of(single().doubleValue() == other.single().doubleValue() ?
					Integer.valueOf(1) : Integer.valueOf(0), lengthLong());
		}

		if (getGranularity() != other.getGranularity() && lengthLong() > Integer.MAX_VALUE) {
			return null;
		}

		if (getMod() == other.getMod()) {
			return IndexSequence.of(type, IntStream.range(0, getMod())
					.parallel()
					.mapToObj(i -> valueAt(i).doubleValue() == other.valueAt(i).doubleValue() ?
							Integer.valueOf(1) : Integer.valueOf(0))
					.toArray(Number[]::new), lengthLong());
		}

		return IndexSequence.of(type, IntStream.range(0, length())
					.parallel()
					.mapToObj(i -> valueAt(i).doubleValue() == other.valueAt(i).doubleValue() ?
							Integer.valueOf(1) : Integer.valueOf(0))
					.toArray(Number[]::new));
	}

	public IndexSequence subset(long len) {
		if (len == lengthLong())
			return this;

		return new IndexSequence(type,
				Arrays.copyOf(toArray(), Math.toIntExact(len)),
				1, Math.toIntExact(len));
	}

	public IntStream intStream() {
		return stream().mapToInt(Number::intValue);
	}

	public LongStream longStream() {
		return stream().mapToLong(Number::longValue);
	}

	public DoubleStream doubleStream() {
		return stream().mapToDouble(Number::doubleValue);
	}

	public IntStream intValues() {
		return values().mapToInt(Number::intValue);
	}

	public LongStream longValues() {
		return values().mapToLong(Number::longValue);
	}

	public DoubleStream doubleValues() {
		return values().mapToDouble(Number::doubleValue);
	}

	public LongStream matchingIndices(double value) {
		return LongStream.range(0, lengthLong()).filter(i -> valueAt(i).doubleValue() == value);
	}

	public long max() {
		if (isConstant()) return single().longValue();

		if (max == null) {
			if (valueAt(0) instanceof Integer) {
				max = (long) intValues().max().orElseThrow();
			} else if (valueAt(0) instanceof Long) {
				max = longValues().max().orElseThrow();
			} else {
				max = (long) Math.ceil(doubleValues().max().orElseThrow());
			}
		}

		return max;
	}

	public boolean isConstant() { return single() != null; }

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

	@Override
	public int getMod() {
		long g = granularity;
		g = g * super.getMod();

		if (g > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}

		return Math.toIntExact(g);
	}

	public Expression<? extends Number> getExpression(Index index) {
		if (isConstant()) {
			return new IntegerConstant(single().intValue());
		} else if (index instanceof Expression && LongStream.range(0, lengthLong()).allMatch(i -> valueAt(i).doubleValue() == i)) {
			return (Expression<? extends Number>) index;
		} else {
			return KernelSeriesMatcher.match((Expression) index, this, ((Expression) index).isInt());
		}
	}

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
			KernelSeriesProvider.timingPos.addEntry("signature", System.nanoTime() - start);
		}
	}

	public static IndexSequence of(Expression<?> exp, IndexValues values, long len) {
		return values.apply(exp).sequence(new KernelIndex(), len);
	}

	public static IndexSequence of(SequenceGenerator source, Index index, long len) {
		if (len > Integer.MAX_VALUE)
			throw new IllegalArgumentException();

		return of(IntStream.range(0, Math.toIntExact(len)).parallel()
				.mapToObj(i -> source.value(new IndexValues().put(index, i))).toArray(Number[]::new));
	}

	public static IndexSequence of(Number[] values) {
		return of(null, values);
	}

	public static IndexSequence of(int[] values) {
		return IndexSequence.of(Integer.class,
				IntStream.of(values).boxed().toArray(Number[]::new));
	}

	public static IndexSequence of(Class<? extends Number> type, Number[] values) {
		return new IndexSequence((Class) type, values, 1, values.length);
	}

	public static IndexSequence of(Class<? extends Number> type, Number[] values, long len) {
		return new IndexSequence((Class) type, values, 1, len);
	}

	public static IndexSequence of(Class<? extends Number> type, Number[] values,
								   int granularity, long len) {
		return new IndexSequence((Class) type, values, granularity, len);
	}

	public static IndexSequence of(Number value, long len) {
		return new IndexSequence(value, len);
	}
}
