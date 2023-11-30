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

package org.almostrealism.hardware.cl;

import org.almostrealism.hardware.RAM;
import org.almostrealism.nio.NativeBuffer;
import org.jocl.Pointer;

public class PointerAndObject<T> {
	private final T obj;
	private final Pointer ptr;

	private PointerAndObject(T obj, Pointer ptr) {
		this.obj = obj;
		this.ptr = ptr;
	}

	public T getObject() { return obj; }

	public Pointer getPointer() { return ptr; }

	public static PointerAndObject<RAM> of(RAM ram) {
		if (!(ram instanceof NativeBuffer)) {
			throw new UnsupportedOperationException();
		}

		return new PointerAndObject<>(ram, Pointer.to(((NativeBuffer) ram).getBuffer()));
	}

	public static PointerAndObject<double[]> of(double d[]) {
		return new PointerAndObject<>(d, Pointer.to(d));
	}

	public static PointerAndObject<float[]> of(float f[]) {
		return new PointerAndObject<>(f, Pointer.to(f));
	}

	public static PointerAndObject<?> forLength(int size, int len) {
		if (size == 4) {
			return PointerAndObject.of(new float[len]);
		} else if (size == 8) {
			return PointerAndObject.of(new double[len]);
		} else {
			throw new IllegalArgumentException(String.valueOf(len));
		}
	}
}
