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
import io.almostrealism.uml.Signature;

import java.util.stream.LongStream;
import java.util.stream.Stream;

public interface Sequence<T> extends Plural<T>, Signature {
	@Override
	default T valueAt(int pos) { return valueAt((long) pos); }

	T valueAt(long pos);

	default int intAt(int pos) { return ((Number) valueAt(pos)).intValue(); }

	default double doubleAt(int pos) { return ((Number) valueAt(pos)).doubleValue(); }

	T[] distinct();

	default Stream<T> stream() {
		return LongStream.range(0, length()).mapToObj(this::valueAt);
	}

	default Stream<T> values() {
		return stream();
	}

	default int length()  { return Math.toIntExact(lengthLong()); }

	long lengthLong();

	T[] toArray();

	Class<? extends T> getType();
}
