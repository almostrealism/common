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
import io.almostrealism.uml.Plural;
import io.almostrealism.util.ArrayItem;
import io.almostrealism.util.Sequence;

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

public interface IndexSequence extends Sequence<Number> {

	IndexSequence map(UnaryOperator<Number> op);

	IndexSequence mapInt(IntUnaryOperator op);

	IndexSequence mapDouble(DoubleUnaryOperator op);

	IndexSequence mod(int m);

	IndexSequence eq(IndexSequence other);

	IndexSequence subset(long len);

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

	Expression<? extends Number> getExpression(Index index);
}
