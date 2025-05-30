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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ArrayItem<T> implements Sequence<T> {
	public static boolean enableCalculateMod = false;

	private T[] values;
	private T single;
	private int mod;
	private long len;

	protected Class<T> type;
	private IntFunction<T[]> generator;

	public ArrayItem(T[] values, IntFunction<T[]> generator) {
		this(null, values, generator);
	}


	public ArrayItem(Class<T> type, T[] values, IntFunction<T[]> generator) {
		this(type, values, values.length, generator);
	}

	public ArrayItem(Class<T> type, T[] values, long len, IntFunction<T[]> generator) {
		this.type = type;
		this.len = len;

		if (values.length < 1 || len < 1) {
			throw new IllegalArgumentException();
		} else if (values.length == 1 || !Stream.of(values).anyMatch(v -> !Objects.equals(v, values[0]))) {
			this.single = values[0];
			this.mod = 1;
		} else if (enableCalculateMod && len < Integer.MAX_VALUE && values.length == len) {
			this.values = values;
			this.mod = calculateMod(values);
		} else {
			this.values = values;
			this.mod = values.length;
		}

		this.generator = generator;
	}

	public ArrayItem(T value, long len, IntFunction<T[]> generator) {
		this.mod = 1;
		this.len = len;
		this.single = value;
		this.type = (Class) value.getClass();
		this.generator = generator;
	}

	@Override
	public T valueAt(long pos) {
		pos = pos % mod;

		if (pos > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}

		return values == null ? single : values[(int) pos];
	}
	
	protected <V> V[] apply(Function<T, V> f, IntFunction<V[]> generator) {
		if (single == null) {
			return Stream.of(values).map(f).toArray(generator);
		} else {
			return IntStream.range(0, 1).mapToObj(i -> f.apply(single)).toArray(generator);
		}
	}

	@Override
	public T[] distinct() {
		return values().distinct().toArray(generator);
	}

	protected T single() { return single; }

	@Override
	public Stream<T> values() {
		return values == null ? Stream.of(single) : Stream.of(values);
	}

	@Override
	public Class<? extends T> getType() {
		if (type == null) {
			if (single != null) {
				type = (Class) single.getClass();
			} else {
				List<Class<?>> types = stream().map(Object::getClass).distinct().collect(Collectors.toList());

				if (types.size() > 1) {
					throw new RuntimeException();
				}

				type = (Class) types.get(0);
			}
		}

		return type;
	}

	@Override
	public T[] toArray() {
		if (values == null || mod < len) {
			return IntStream.range(0, length())
					.mapToObj(i -> valueAt(i))
					.toArray(generator);
		}

		return values;
	}

	public long getMod() { return mod; }

	@Override
	public long lengthLong() { return len; }

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

	public int computeMod() {
		return calculateMod(toArray());
	}

	public static <T> int calculateMod(T values[]) {
		Set<T> existing = new HashSet<>();

		int mod = -1;

		i: for (int i = 0; i < values.length; i++) {
			if (existing.size() > 1 && existing.contains(values[i])) {
				mod = i;
				break i;
			}

			existing.add(values[i]);
		}

		if (mod == -1) {
			return values.length;
		}

		for (int i = 0; i < values.length; i++) {
			int row = i / mod;

			if (row > 0) {
				int col = i % mod;
				int compareIdx = (row - 1) * mod + col;
				if (!values[compareIdx].equals(values[i])) {
					return values.length;
				}
			}
		}

		return mod;
	}
}
