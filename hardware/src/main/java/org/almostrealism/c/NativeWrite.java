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

package org.almostrealism.c;

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeCompiler;

/**
 * JNI wrapper for writing data from Java arrays into native memory.
 *
 * <p>{@link NativeWrite} provides a native operation that copies data from Java double arrays
 * into native memory (allocated via {@link Malloc} or {@link NativeMemoryProvider}).
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
 * <p>All write operations validate that the destination memory is large enough
 * for the requested write operation.</p>
 *
 * @see NativeRead
 * @see NativeMemory
 * @see NativeMemoryProvider
 * @see BaseNative
 */
public class NativeWrite extends BaseNative {
	/**
	 * Creates a new NativeWrite operation using the specified compiler.
	 *
	 * <p>Compiles and loads the native library immediately upon construction.</p>
	 *
	 * @param compiler The {@link NativeCompiler} for compiling the C code
	 */
	public NativeWrite(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	/**
	 * Returns the C function definition for the native write operation.
	 *
	 * <p>Generates a JNI-compatible C function that writes data from a Java double array
	 * into native memory. The function handles precision conversion:
	 * FP64 mode writes doubles directly, FP32 mode converts doubles to floats.</p>
	 *
	 * @return The C code defining the JNI write function
	 */
	@Override
	public String getFunctionDefinition() {
		if (getNativeCompiler().getPrecision() == Precision.FP64) {
			return "JNIEXPORT void JNICALL " + getFunctionName() +
					" (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jdoubleArray target, jint toffset, jint len) {\n" +
					(enableVerbose ? "\tprintf(\"nativeWrite(%lu) - %i values\\n\", arg, len);\n" : "") +
					"\tdouble* input = (*env)->GetDoubleArrayElements(env, target, 0);\n" +
					"\tdouble* output = (double *) arg;\n" +
					"\tfor (int i = 0; i < len; i++) {\n" +
					"\t\toutput[offset + i] = input[toffset + i];\n" +
					"\t}" +
					"\tfree(input);\n" +
					"}\n";
		} else {
			return "JNIEXPORT void JNICALL " + getFunctionName() +
					" (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jdoubleArray target, jint toffset, jint len) {\n" +
					(enableVerbose ? "\tprintf(\"nativeWrite(%lu) - %i values\\n\", arg, len);\n" : "") +
					"\tdouble* input = (*env)->GetDoubleArrayElements(env, target, 0);\n" +
					"\tfloat* output = (float *) arg;\n" +
					"\tfor (int i = 0; i < len; i++) {\n" +
					"\t\toutput[offset + i] = (float) input[toffset + i];\n" +
					"\t}" +
					"\tfree(input);\n" +
					"}\n";
		}
	}

	/**
	 * Reads all data from a {@link MemoryData} into a new double array.
	 *
	 * <p><strong>Note:</strong> This method appears misnamed as it performs a read operation
	 * rather than a write. Consider using {@link NativeRead} for reading operations.</p>
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
	 * Writes all data from the target array to native memory starting at offset 0.
	 *
	 * @param mem    The native memory to write to
	 * @param target The source array containing data to write
	 */
	public void apply(NativeMemory mem, double target[]) { apply(mem, 0, target); }

	/**
	 * Writes all data from the target array to native memory starting at the specified offset.
	 *
	 * @param mem    The native memory to write to
	 * @param offset The starting offset in elements within native memory
	 * @param target The source array containing data to write
	 */
	public void apply(NativeMemory mem, int offset, double target[]) {
		apply(mem, offset, target, 0, target.length);
	}

	/**
	 * Writes data from the target array to native memory with full control over offsets and lengths.
	 *
	 * @param mem     The native memory to write to
	 * @param offset  The starting offset in elements within native memory
	 * @param target  The source array containing data to write
	 * @param toffset The starting offset in the source array
	 * @param length  The number of elements to write
	 * @throws IllegalArgumentException if the write would exceed memory bounds
	 */
	public void apply(NativeMemory mem, int offset, double target[], int toffset, int length) {
		if (mem.getSize() < (offset + length) * getNativeCompiler().getPrecision().bytes()) {
			throw new IllegalArgumentException("Attempt to write memory beyond the size of " + mem);
		}

		apply(mem.getContentPointer(), offset, target, toffset, length);
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
	public native void apply(long arg, int offset, double[] target, int toffset, int length);
}
