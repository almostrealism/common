/*
 * Copyright 2023 Michael Murray
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
 * JNI wrapper for reading data from native memory into Java arrays.
 *
 * <p>{@link NativeRead} provides a native operation that copies data from native memory
 * (allocated via {@link Malloc} or {@link org.almostrealism.nio.NativeMemoryProvider}) into Java double arrays.
 * It handles precision conversion automatically based on the compiler's precision setting.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * NativeRead reader = new NativeRead(compiler);
 *
 * // Read from NativeMemory
 * NativeMemory mem = ...;
 * double[] data = new double[1024];
 * reader.apply(mem, data);  // Read all data
 *
 * // Read with offset
 * reader.apply(mem, 100, data, 0, 500);  // Read 500 elements starting at offset 100
 * }</pre>
 *
 * <h2>Precision Handling</h2>
 *
 * <p>The native code automatically converts between native precision and Java doubles:
 * <ul>
 *   <li>FP64: Direct copy from native {@code double*}</li>
 *   <li>FP32: Converts from native {@code float*} to Java {@code double[]}</li>
 * </ul>
 *
 * <h2>Bounds Checking</h2>
 *
 * <p>All read operations validate that:
 * <ul>
 *   <li>The source memory is large enough for the requested read</li>
 *   <li>The destination array is large enough to hold the data</li>
 * </ul>
 *
 * @see NativeArrayCopy
 * @see NativeWrite
 * @see NativeMemory
 * @see org.almostrealism.nio.NativeMemoryProvider
 * @see BaseNative
 */
public class NativeRead extends NativeArrayCopy {
	/**
	 * Creates a new NativeRead operation using the specified compiler.
	 *
	 * <p>Compiles and loads the native library immediately upon construction.</p>
	 *
	 * @param compiler The {@link NativeCompiler} for compiling the C code
	 */
	public NativeRead(NativeCompiler compiler) {
		super(compiler);
	}

	/**
	 * Returns the C function definition for the native read operation.
	 *
	 * <p>Generates a JNI-compatible C function that copies data from native memory directly
	 * into the caller's Java double array. FP64 mode copies the doubles in a single
	 * {@code SetDoubleArrayRegion}; FP32 mode converts floats to doubles through a fixed-size
	 * stack buffer of {@link #CONVERSION_CHUNK} values.</p>
	 *
	 * <p>Nothing is allocated, on the Java heap or the C heap, however long the read is. A
	 * read the size of the whole memory block would otherwise need a second array of that size,
	 * and a failed allocation is a {@code NULL} that the copy would dereference, crashing the
	 * JVM instead of reporting the shortage.</p>
	 *
	 * @return The C code defining the JNI read function
	 */
	@Override
	public String getFunctionDefinition() {
		String signature = getFunctionSignature();

		if (getNativeCompiler().getPrecision() == Precision.FP64) {
			return signature +
					"\t(*env)->SetDoubleArrayRegion(env, target, toffset, len, ((const jdouble *) arg) + offset);\n" +
					"}\n";
		} else {
			int chunk = CONVERSION_CHUNK;
			return signature +
					"\tconst float* input = ((const float *) arg) + offset;\n" +
					"\tjdouble values[" + chunk + "];\n" +
					"\tfor (jint i = 0; i < len; i += " + chunk + ") {\n" +
					"\t\tjint n = len - i < " + chunk + " ? len - i : " + chunk + ";\n" +
					"\t\tfor (jint j = 0; j < n; j++) {\n" +
					"\t\t\tvalues[j] = (jdouble) input[i + j];\n" +
					"\t\t}\n" +
					"\t\t(*env)->SetDoubleArrayRegion(env, target, toffset + i, n, values);\n" +
					"\t\tif ((*env)->ExceptionCheck(env)) return;\n" +
					"\t}\n" +
					"}\n";
		}
	}

	/**
	 * Native method that copies data from a memory address into an existing Java double array.
	 *
	 * <p>This is a JNI method that invokes native code to read memory. The native code writes
	 * directly into {@code target} and allocates nothing (see {@link #getFunctionDefinition()}).
	 * The caller is responsible for the bounds of both the memory and the array.</p>
	 *
	 * @param arg     The native memory address to read from
	 * @param offset  The starting offset in elements within native memory
	 * @param target  The destination array
	 * @param toffset The starting offset in the destination array
	 * @param length  The number of elements to read
	 */
	@Override
	protected native void apply(long arg, int offset, double[] target, int toffset, int length);
}
