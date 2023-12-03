/*
 * Copyright 2021 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.uml.Named;

import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public interface MemoryProvider<T extends Memory> extends Named {
	int getNumberSize();

	T allocate(int size);

	void deallocate(int size, T mem);

	default T reallocate(Memory mem, int offset, int length) {
		T newMem = allocate(length);
		setMem(newMem, 0, mem, offset, length);
		return newMem;
	}

	default double[] toArray(T mem, int length) {
		return toArray(mem, 0, length);
	}

	default double[] toArray(T mem, int offset, int length) {
		int attempt = 1; //5;

		return IntStream.range(0, attempt).mapToObj(i -> {
			double d[] = new double[length];
			getMem(mem, offset, d, 0, length);
			return d;
		}).collect(Collectors.toList()).get(attempt - 1);
	}

	void setMem(T mem, int offset, Memory source, int srcOffset, int length);

	default void setMem(T mem, int offset, float[] source, int srcOffset, int length) {
		setMem(mem, offset, IntStream.range(0, source.length).mapToDouble(i -> source[i]).toArray(), srcOffset, length);
	}

	void setMem(T mem, int offset, double[] source, int srcOffset, int length);

	default void getMem(T mem, int sOffset, float out[], int oOffset, int length) {
		double d[] = new double[length];
		getMem(mem, sOffset, d, 0, length);
		for (int i = 0; i < length; i++) {
			out[i] = (float) d[i];
		}
	}

	void getMem(T mem, int sOffset, double out[], int oOffset, int length);

	void destroy();
}
