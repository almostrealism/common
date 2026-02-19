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

package io.almostrealism.code;

import io.almostrealism.uml.Named;

import java.util.stream.IntStream;

/** The MemoryProvider interface. */
public interface MemoryProvider<T extends Memory> extends Named {
	int MAX_RESERVATION = Integer.MAX_VALUE / Precision.FP64.bytes();

	/** Performs the getNumberSize operation. */
	int getNumberSize();

	/** Performs the allocate operation. */
	T allocate(int size);

	/** Performs the deallocate operation. */
	void deallocate(int size, T mem);

	/** Performs the reallocate operation. */
	default T reallocate(Memory mem, int offset, int length) {
		T newMem = allocate(length);
		setMem(newMem, 0, mem, offset, length);
		return newMem;
	}

	/** Performs the toArray operation. */
	default double[] toArray(T mem, int length) {
		return toArray(mem, 0, length);
	}

	/** Performs the toArray operation. */
	default double[] toArray(T mem, int offset, int length) {
		double d[] = new double[length];
		getMem(mem, offset, d, 0, length);
		return d;
	}

	/** Performs the setMem operation. */
	void setMem(T mem, int offset, Memory source, int srcOffset, int length);

	/** Performs the setMem operation. */
	default void setMem(T mem, int offset, float[] source, int srcOffset, int length) {
		setMem(mem, offset, IntStream.range(0, source.length).mapToDouble(i -> source[i]).toArray(), srcOffset, length);
	}

	/** Performs the setMem operation. */
	void setMem(T mem, int offset, double[] source, int srcOffset, int length);

	/** Performs the getMem operation. */
	default void getMem(T mem, int sOffset, float out[], int oOffset, int length) {
		double d[] = new double[length];
		getMem(mem, sOffset, d, 0, length);
		for (int i = 0; i < length; i++) {
			out[i] = (float) d[i];
		}
	}

	/** Performs the getMem operation. */
	void getMem(T mem, int sOffset, double out[], int oOffset, int length);

	/** Performs the destroy operation. */
	void destroy();
}
