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

package org.almostrealism.nio;

import io.almostrealism.code.Precision;
import org.almostrealism.c.BaseNative;
import org.almostrealism.hardware.jni.NativeCompiler;

/**
 * JNI wrapper for writing data from Java arrays into native memory.
 *
 * <p>{@link NativeWrite} provides a native operation that copies data from Java double arrays
 * into native memory (allocated via {@link Malloc} or {@link org.almostrealism.nio.NativeMemoryProvider}).
 * It handles precision conversion automatically based on the compiler's precision setting.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * NativeWrite writer = new NativeWrite(compiler);
 *
 * // Write to NativeMemory
 * NativeMemory mem = ...;
 * double[] data = {1.0, 2.0, 3.0, 4.0};
 * writer.apply(mem, data);  // Write all data starting at offset 0
 *
 * // Write with offset
 * writer.apply(mem, 100, data, 0, 4);  // Write 4 elements starting at offset 100
 * }</pre>
 *
 * <h2>Precision Handling</h2>
 *
 * <p>The native code automatically converts between Java doubles and native precision:
 * <ul>
 *   <li>FP64: Direct copy from Java {@code double[]} to native {@code double*}</li>
 *   <li>FP32: Converts Java {@code double[]} to native {@code float*}</li>
 * </ul>
 *
 * <h2>Bounds Checking</h2>
 *
 * <p>All write operations validate that:
 * <ul>
 *   <li>The destination memory is large enough for the requested write</li>
 *   <li>The source array holds the requested range</li>
 * </ul>
 *
 * @see NativeArrayCopy
 * @see NativeRead
 * @see NativeMemory
 * @see org.almostrealism.nio.NativeMemoryProvider
 * @see BaseNative
 */
public class NativeWrite extends NativeArrayCopy {
	/**
	 * Creates a new NativeWrite operation using the specified compiler.
	 *
	 * <p>Compiles and loads the native library immediately upon construction.</p>
	 *
	 * @param compiler The {@link NativeCompiler} for compiling the C code
	 */
	public NativeWrite(NativeCompiler compiler) {
		super(compiler);
	}

	/**
	 * Returns the C function definition for the native write operation.
	 *
	 * <p>Generates a JNI-compatible C function that writes data from a Java double array
	 * directly into native memory. FP64 mode copies the doubles in a single
	 * {@code GetDoubleArrayRegion}; FP32 mode converts doubles to floats through a fixed-size
	 * stack buffer of {@link #CONVERSION_CHUNK} values.</p>
	 *
	 * <p>The array is never pinned or copied as a whole, so nothing is allocated however long
	 * the write is, and no pointer obtained from the JVM has to be handed back to it (the JVM's
	 * array elements must be released through JNI, never passed to {@code free()}).</p>
	 *
	 * @return The C code defining the JNI write function
	 */
	@Override
	public String getFunctionDefinition() {
		String signature = getFunctionSignature();

		if (getNativeCompiler().getPrecision() == Precision.FP64) {
			return signature +
					"\t(*env)->GetDoubleArrayRegion(env, target, toffset, len, ((jdouble *) arg) + offset);\n" +
					"}\n";
		} else {
			int chunk = CONVERSION_CHUNK;
			return signature +
					"\tfloat* output = ((float *) arg) + offset;\n" +
					"\tjdouble values[" + chunk + "];\n" +
					"\tfor (jint i = 0; i < len; i += " + chunk + ") {\n" +
					"\t\tjint n = len - i < " + chunk + " ? len - i : " + chunk + ";\n" +
					"\t\t(*env)->GetDoubleArrayRegion(env, target, toffset + i, n, values);\n" +
					"\t\tif ((*env)->ExceptionCheck(env)) return;\n" +
					"\t\tfor (jint j = 0; j < n; j++) {\n" +
					"\t\t\toutput[i + j] = (float) values[j];\n" +
					"\t\t}\n" +
					"\t}\n" +
					"}\n";
		}
	}

	/**
	 * Native method that writes data from a Java double array to a memory address.
	 *
	 * <p>This is a JNI method that invokes native code to write memory. The native code
	 * copies data from the Java array to the specified memory address.</p>
	 *
	 * @param arg     The native memory address to write to
	 * @param offset  The starting offset in elements within native memory
	 * @param target  The source array containing data to write
	 * @param toffset The starting offset in the source array
	 * @param length  The number of elements to write
	 */
	@Override
	protected native void apply(long arg, int offset, double[] target, int toffset, int length);
}
