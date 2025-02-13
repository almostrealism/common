/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.heredity;

import io.almostrealism.relation.Factor;
import io.almostrealism.uml.Plural;

import java.util.function.IntFunction;
import java.util.stream.IntStream;

public interface Gene<T> extends Plural<Factor<T>>, IntFunction<Factor<T>> {
	@Override
	default Factor<T> apply(int pos) { return valueAt(pos); }

	int length();

	default String signature() {
		StringBuffer buf = new StringBuffer();
		IntStream.range(0, length()).mapToObj(this::valueAt).map(Factor::signature).forEach(buf::append);
		return buf.toString();
	}

	static <T> Gene<T> of(int length, IntFunction<Factor<T>> factor) {
		return new Gene<T>() {
			@Override
			public Factor<T> valueAt(int pos) {
				return factor.apply(pos);
			}

			@Override
			public int length() {
				return length;
			}
		};
	}
}
