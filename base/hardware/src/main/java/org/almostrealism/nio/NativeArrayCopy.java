/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.nio;

import org.almostrealism.c.BaseNative;
import org.almostrealism.hardware.jni.NativeCompiler;

/**
 * A JNI operation that copies values between {@link NativeMemory} and a Java {@code double[]},
 * in one direction: {@link NativeRead} copies from the memory into the array, and
 * {@link NativeWrite} copies from the array into the memory.
 *
 * <p>Both directions share one native signature,
 * {@code apply(long address, int offset, double[] array, int arrayOffset, int length)}, which
 * each subclass declares itself (JNI binds a native method to the class that declares it). The
 * generated code copies directly between the memory and the caller's array and allocates
 * nothing: in FP64 mode a single array-region call, in FP32 mode a conversion through a
 * fixed-size stack buffer of {@link #CONVERSION_CHUNK} values.</p>
 *
 * <p>This class owns what the two directions have in common: the chunk size, the generated
 * function signature, the bounds checks against the memory and the array, and the overloads
 * through which every copy is made.</p>
 *
 * @see NativeRead
 * @see NativeWrite
 */
public abstract class NativeArrayCopy extends BaseNative {
	/**
	 * The number of values converted at a time, through a stack buffer, when a copy between a
	 * Java {@code double[]} and FP32 native memory changes precision.
	 */
	public static final int CONVERSION_CHUNK = 1024;

	/**
	 * Creates a copy operation and compiles and loads its native code.
	 *
	 * @param compiler the {@link NativeCompiler} for compiling the C code
	 */
	protected NativeArrayCopy(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	/**
	 * Returns the opening of the generated JNI function, up to and including its opening brace,
	 * followed by a verbose trace of the call when {@link #enableVerbose} is set.
	 *
	 * @return the C function header for this copy
	 */
	protected String getFunctionSignature() {
		return "JNIEXPORT void JNICALL " + getFunctionName() +
				" (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jdoubleArray target, jint toffset, jint len) {\n" +
				(enableVerbose ? "\tprintf(\"" + getClass().getSimpleName() + "(%lu) - %i values\\n\", arg, len);\n" : "");
	}

	/**
	 * Copies between all of {@code array} and the memory, starting at offset 0 in the memory.
	 *
	 * @param mem   the native memory
	 * @param array the Java array
	 */
	public void apply(NativeMemory mem, double[] array) { apply(mem, 0, array); }

	/**
	 * Copies between all of {@code array} and the memory, starting at {@code offset} in the memory.
	 *
	 * @param mem    the native memory
	 * @param offset the starting offset in elements within the memory
	 * @param array  the Java array
	 */
	public void apply(NativeMemory mem, int offset, double[] array) {
		apply(mem, offset, array, 0, array.length);
	}

	/**
	 * Copies {@code length} values between the memory, starting at {@code offset}, and
	 * {@code array}, starting at {@code arrayOffset}, after checking that the range lies within
	 * both. Both directions are held to the same bounds, so the native copy never runs past the
	 * end of the memory or the array.
	 *
	 * @param mem         the native memory
	 * @param offset      the starting offset in elements within the memory
	 * @param array       the Java array
	 * @param arrayOffset the starting offset in the array
	 * @param length      the number of values to copy
	 * @throws IllegalArgumentException if the copy would exceed the memory or the array
	 */
	public final void apply(NativeMemory mem, int offset, double[] array, int arrayOffset, int length) {
		if (mem.getSize() < (long) (offset + length) * getNativeCompiler().getPrecision().bytes()) {
			throw new IllegalArgumentException("Attempt to access memory beyond the size of " + mem);
		} else if (array.length < arrayOffset + length) {
			throw new IllegalArgumentException("Attempt to access an array beyond its size");
		}

		if (length > 0) apply(mem.getContentPointer(), offset, array, arrayOffset, length);
	}

	/**
	 * Copies {@code length} values between the memory at {@code address}, starting at
	 * {@code offset}, and {@code array}, starting at {@code arrayOffset}, in native code.
	 *
	 * <p>Each subclass implements this as its {@code native} method, because JNI binds a native
	 * method to the class that declares it. Nothing is checked here; the bounds are the
	 * responsibility of {@link #apply(NativeMemory, int, double[], int, int)}.</p>
	 *
	 * @param address     the native memory address
	 * @param offset      the starting offset in elements within the memory
	 * @param array       the Java array
	 * @param arrayOffset the starting offset in the array
	 * @param length      the number of values to copy
	 */
	protected abstract void apply(long address, int offset, double[] array, int arrayOffset, int length);
}
