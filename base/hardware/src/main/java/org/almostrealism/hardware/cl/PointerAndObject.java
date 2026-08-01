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

import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.nio.NativeBuffer;
import org.jocl.Pointer;

/**
 * Pair of JOCL {@link Pointer} and its associated Java object.
 *
 * <p>Used by {@link CLMemoryProvider} to track host pointers (arrays, {@link NativeBuffer})
 * associated with OpenCL {@link org.jocl.cl_mem} objects for heap-based allocation.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * // For arrays
 * float[] data = new float[1024];
 * PointerAndObject<float[]> pair = PointerAndObject.of(data);
 *
 * // For NativeBuffer
 * NativeBuffer buffer = ...;
 * PointerAndObject<RAM> pair = PointerAndObject.of(buffer);
 *
 * // Use in cl_mem creation
 * cl_mem mem = CL.clCreateBuffer(ctx, CL_MEM_USE_HOST_PTR, size,
 *     pair.getPointer(), null);
 * }</pre>
 *
 * @param <T> The type of object (float[], double[], or RAM)
 * @see CLMemoryProvider
 */
public class PointerAndObject<T> {
	/** The Java object associated with this pointer. */
	private final T obj;
	/** Native pointer to the memory backing {@code obj}. */
	private final Pointer ptr;

	/**
	 * Creates a pointer-and-object pair.
	 *
	 * @param obj Java object to pair with the pointer
	 * @param ptr Native pointer to the object's backing memory
	 */
	private PointerAndObject(T obj, Pointer ptr) {
		this.obj = obj;
		this.ptr = ptr;
	}

	/**
	 * Returns the Java object associated with this pointer.
	 *
	 * @return The associated object
	 */
	public T getObject() { return obj; }

	/**
	 * Returns the native pointer to the object's backing memory.
	 *
	 * @return Native pointer
	 */
	public Pointer getPointer() { return ptr; }

	/**
	 * Creates a pointer-and-object pair for the given RAM instance.
	 *
	 * @param ram RAM instance; must be a {@link NativeBuffer}
	 * @return Pointer-and-object pair for the RAM
	 * @throws UnsupportedOperationException if the RAM is not a NativeBuffer
	 */
	public static PointerAndObject<RAM> of(RAM ram) {
		if (!(ram instanceof NativeBuffer)) {
			throw new UnsupportedOperationException();
		}

		return new PointerAndObject<>(ram, Pointer.to(((NativeBuffer) ram).getBuffer()));
	}

	/**
	 * Creates a pointer-and-object pair for the given double array.
	 *
	 * @param d Double array to wrap
	 * @return Pointer-and-object pair for the double array
	 */
	public static PointerAndObject<double[]> of(double d[]) {
		return new PointerAndObject<>(d, Pointer.to(d));
	}

	/**
	 * Creates a pointer-and-object pair for the given float array.
	 *
	 * @param f Float array to wrap
	 * @return Pointer-and-object pair for the float array
	 */
	public static PointerAndObject<float[]> of(float f[]) {
		return new PointerAndObject<>(f, Pointer.to(f));
	}

	/**
	 * Creates a typed pointer-and-object pair for a buffer of the given element size and length.
	 *
	 * @param size Element size in bytes (4 for float, 8 for double)
	 * @param len  Number of elements to allocate
	 * @return Pointer-and-object pair for the new buffer
	 * @throws IllegalArgumentException if size is not 4 or 8
	 */
	public static PointerAndObject<?> forLength(int size, int len) {
		if (size == 4) {
			return PointerAndObject.of(new float[len]);
		} else if (size == 8) {
			return PointerAndObject.of(new double[len]);
		} else {
			throw new IllegalArgumentException(String.valueOf(len));
		}
	}

	@Override
	public int hashCode() {
		return obj.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof PointerAndObject) {
			return obj.equals(((PointerAndObject) o).obj);
		} else {
			return false;
		}
	}
}
