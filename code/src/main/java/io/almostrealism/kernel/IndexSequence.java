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
	private Base64.Encoder encoder = Base64.getEncoder();
	private Long max;

	protected IndexSequence(Class<Number> type, Number[] values) {
		super(type, values, Number[]::new);
	}

	protected IndexSequence(Number value, int len) {
		super(value, len, Number[]::new);
	}

	public IndexSequence map(UnaryOperator<Number> op) {
		return IndexSequence.of(stream().map(op).toArray(Number[]::new));
	}

	public IndexSequence mapInt(IntUnaryOperator op) {
		return IndexSequence.of(Integer.class, intStream().map(op).boxed().toArray(Number[]::new));
	}

	public IndexSequence mapDouble(DoubleUnaryOperator op) {
		return IndexSequence.of(Double.class, doubleStream().map(op).boxed().toArray(Number[]::new));
	}

	public IndexSequence subset(int len) {
		if (len == length()) return this;
		return new IndexSequence(type, Arrays.copyOf(toArray(), len));
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

	public IntStream matchingIndices(double value) {
		return IntStream.range(0, length()).filter(i -> valueAt(i).doubleValue() == value);
	}

	public long max() {
		if (isConstant()) return single().longValue();

		if (max == null) {
			if (valueAt(0) instanceof Integer) {
				max = (long) intStream().max().orElseThrow();
			} else if (valueAt(0) instanceof Long) {
				max = longStream().max().orElseThrow();
			} else {
				max = (long) Math.ceil(doubleStream().max().orElseThrow());
			}
		}

		return max;
	}

	public boolean isConstant() { return single() != null; }

	public int getGranularity() {
		if (single() != null) return length();

		int granularity = 1;

		i: for (int i = 0; i < length() - 1; i++) {
			if (!Objects.equals(valueAt(i), valueAt(i + 1))) {
				granularity = i + 1;
				break i;
			}
		}

		if (granularity == 1) return granularity;

		int sections = length() / granularity;

		for (int i = 0; i < sections; i++) {
			for (int j = 1; j < granularity & i * granularity + j < length(); j++) {
				if (!Objects.equals(valueAt(i * granularity), valueAt(i * granularity + j))) {
					return 1;
				}
			}
		}

		return granularity;
	}

	public Expression<? extends Number> getExpression(Index index) {
		if (isConstant()) {
			return new IntegerConstant(single().intValue());
		} else if (index instanceof Expression && IntStream.range(0, length()).allMatch(i -> valueAt(i).doubleValue() == i)) {
			return (Expression<? extends Number>) index;
		} else {
			return KernelSeriesMatcher.match(((Expression) index), this, ((Expression) index).getType() == Integer.class);
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

	public static IndexSequence of(Expression<?> exp, IndexValues values, int len) {
		return values.apply(exp).sequence(new KernelIndex(), len);
	}

	public static IndexSequence of(SequenceGenerator source, Index index, int len) {
		return of(IntStream.range(0, len).parallel()
				.mapToObj(i -> source.value(new IndexValues().put(index, i))).toArray(Number[]::new));
	}

	public static IndexSequence of(Number[] values) {
		return of(null, values);
	}

	public static IndexSequence of(Class<? extends Number> type, Number[] values) {
		return new IndexSequence((Class) type, values);
	}

	public static IndexSequence of(Number value, int len) {
		return new IndexSequence(value, len);
	}
}
