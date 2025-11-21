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

package org.almostrealism.c;

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeCompiler;

/**
 * JNI wrapper for reading data from native memory into Java arrays.
 *
 * <p>{@link NativeRead} provides a native operation that copies data from native memory
 * (allocated via {@link Malloc} or {@link NativeMemoryProvider}) into Java double arrays.
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
 *
 * // Read from MemoryData
 * MemoryData memData = ...;
 * double[] result = reader.apply(memData);  // Creates and returns new array
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
 * @see NativeWrite
 * @see NativeMemory
 * @see NativeMemoryProvider
 * @see BaseNative
 */
public class NativeRead extends BaseNative {
	/**
	 * Creates a new NativeRead operation using the specified compiler.
	 *
	 * <p>Compiles and loads the native library immediately upon construction.</p>
	 *
	 * @param compiler The {@link NativeCompiler} for compiling the C code
	 */
	public NativeRead(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	/**
	 * Returns the C function definition for the native read operation.
	 *
	 * <p>Generates a JNI-compatible C function that reads data from native memory
	 * into a Java double array. The function handles precision conversion:
	 * FP64 mode reads doubles directly, FP32 mode converts floats to doubles.</p>
	 *
	 * @return The C code defining the JNI read function
	 */
	@Override
	public String getFunctionDefinition() {
		if (getNativeCompiler().getPrecision() == Precision.FP64) {
			return "JNIEXPORT jdoubleArray JNICALL " + getFunctionName() +
					" (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jint len) {\n" +
					(enableVerbose ? "\tprintf(\"nativeRead(%lu) - %i values\\n\", arg, len);\n" : "") +
					"\tdouble* input = (double *) arg;\n" +
					"\tjdoubleArray output = (*env)->NewDoubleArray(env, (jsize) len);\n" +
					"\tfor (int i = 0; i < len; i++) {\n" +
					"\t\t(*env)->SetDoubleArrayRegion(env, output, i, 1, (const jdouble*)&input[offset + i]);\n" +
					"\t}\n" +
					"return output;\n" +
					"}\n";
		} else {
			return "JNIEXPORT jdoubleArray JNICALL " + getFunctionName() +
					" (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jint len) {\n" +
					(enableVerbose ? "\tprintf(\"nativeRead(%lu) - %i values\\n\", arg, len);\n" : "") +
					"\tfloat* input = (float *) arg;\n" +
					"\tjdoubleArray output = (*env)->NewDoubleArray(env, (jsize) len);\n" +
					"\tfor (int i = 0; i < len; i++) {\n" +
					"\t\tjdouble value = (jdouble) input[offset + i];\n" +
					"\t\t(*env)->SetDoubleArrayRegion(env, output, i, 1, &value);\n" +
					"\t}" +
					"return output;\n" +
					"}\n";
		}
	}

	/**
	 * Reads all data from a {@link MemoryData} into a new double array.
	 *
	 * @param mem The memory data to read from
	 * @return A new array containing all values from the memory
	 */
	public double[] apply(MemoryData mem) {
		double out[] = new double[mem.getMemLength()];
		apply((NativeMemory) mem.getMem(), out);
		return out;
	}

	/**
	 * Reads all data from native memory starting at offset 0.
	 *
	 * @param mem    The native memory to read from
	 * @param target The destination array to populate
	 */
	public void apply(NativeMemory mem, double target[]) { apply(mem, 0, target); }

	/**
	 * Reads data from native memory starting at the specified offset.
	 *
	 * @param mem    The native memory to read from
	 * @param offset The starting offset in elements
	 * @param target The destination array to populate
	 */
	public void apply(NativeMemory mem, int offset, double target[]) {
		apply(mem, offset, target, 0, target.length);
	}

	/**
	 * Reads data from native memory with full control over offsets and lengths.
	 *
	 * @param mem     The native memory to read from
	 * @param offset  The starting offset in elements within native memory
	 * @param target  The destination array to populate
	 * @param toffset The starting offset in the destination array
	 * @param length  The number of elements to read
	 * @throws IllegalArgumentException if the read would exceed memory bounds or array bounds
	 */
	public void apply(NativeMemory mem, int offset, double target[], int toffset, int length) {
		if (mem.getSize() < (offset + length) * getNativeCompiler().getPrecision().bytes()) {
			throw new IllegalArgumentException("Attempt to read memory beyond the size of " + mem);
		} else if (target.length < toffset + length) {
			throw new IllegalArgumentException("Attempt to read memory into a destination array beyond the array size");
		}

		double out[] = apply(mem.getContentPointer(), offset, length);
		if (length >= 0) System.arraycopy(out, 0, target, toffset, length);
	}

	/**
	 * Native method that reads data from a memory address into a new Java double array.
	 *
	 * <p>This is a JNI method that invokes native code to read memory. The native code
	 * creates a new Java double array and copies data from the specified memory address.</p>
	 *
	 * @param arg    The native memory address to read from
	 * @param offset The starting offset in elements
	 * @param length The number of elements to read
	 * @return A new array containing the read values
	 */
	public native double[] apply(long arg, int offset, int length);
}
