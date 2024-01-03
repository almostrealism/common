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

package io.almostrealism.util;

import io.almostrealism.uml.Plural;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ArrayItem<T> implements Plural<T> {
	private T[] values;
	private T single;
	private int len;

	private IntFunction<T[]> generator;

	public ArrayItem(T[] values, IntFunction<T[]> generator) {
		if (Stream.of(values).distinct().count() == 1) {
			this.single = values[0];
			this.len = values.length;
		} else {
			this.values = values;
		}

		this.generator = generator;
	}

	@Override
	public T valueAt(int pos) { return values == null ? single : values[pos]; }


	public T[] toArray() {
		return values == null ? IntStream.range(0, len).mapToObj(i -> single).toArray(generator) : values;
	}

	@Override
	public int hashCode() {
		return values == null ? single.hashCode() : Arrays.hashCode(values);
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ArrayItem)) return false;

		ArrayItem it = (ArrayItem) obj;
		if (values == null && Objects.equals(single, it.single)) return true;
		return Arrays.equals(toArray(), it.toArray());
	}
}
