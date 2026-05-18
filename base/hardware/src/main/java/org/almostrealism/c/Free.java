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
import org.almostrealism.hardware.jni.NativeCompiler;

/**
 * JNI wrapper for freeing native memory allocated by {@link Malloc}.
 *
 * <p>{@link Free} provides a native {@code free()} operation that releases memory
 * previously allocated via JNI. It generates and compiles a C function that casts
 * the pointer to the appropriate precision type (float/double) before freeing.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Allocate native memory
 * Malloc malloc = new Malloc(compiler);
 * long ptr = malloc.apply(1024);  // Allocate 1024 elements
 *
 * // Use memory...
 *
 * // Free native memory
 * Free free = new Free(compiler);
 * free.apply(ptr);  // Releases the memory
 * }</pre>
 *
 * <h2>Precision Handling</h2>
 *
 * <p>The generated C code casts the pointer based on the compiler's precision setting:
 * <ul>
 *   <li>FP64: Casts to {@code double*} before freeing</li>
 *   <li>FP32: Casts to {@code float*} before freeing</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The native {@code free()} function is thread-safe when called with different pointers,
 * but calling {@code free()} on the same pointer from multiple threads is undefined behavior.</p>
 *
 * @see Malloc
 * @see BaseNative
 * @see NativeMemoryProvider
 */
public class Free extends BaseNative {

	/**
	 * Creates a new Free operation using the specified compiler.
	 *
	 * <p>Compiles and loads the native library immediately upon construction.</p>
	 *
	 * @param compiler The {@link NativeCompiler} for compiling the C code
	 */
	public Free(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	/**
	 * Returns the C function definition for the native free operation.
	 *
	 * <p>Generates a JNI-compatible C function that frees memory at the address
	 * specified by the {@code jlong val} parameter. The pointer is cast to the
	 * appropriate type based on precision (double* for FP64, float* for FP32).</p>
	 *
	 * @return The C code defining the JNI free function
	 */
	@Override
	public String getFunctionDefinition() {
		if (getNativeCompiler().getPrecision() == Precision.FP64) {
			return "JNIEXPORT void JNICALL " + getFunctionName() + " (JNIEnv* env, jobject thisObject, jlong val) {\n" +
					(enableVerbose ? "\tprintf(\"free(%lu)\\n\", val);\n" : "") +
					"\tfree((double *) val);\n" +
					"}\n";
		} else {
			return "JNIEXPORT void JNICALL " + getFunctionName() + " (JNIEnv* env, jobject thisObject, jlong val) {\n" +
					(enableVerbose ? "\tprintf(\"free(%lu)\\n\", val);\n" : "") +
					"\tfree((float *) val);\n" +
					"}\n";
		}
	}

	/**
	 * Frees native memory at the specified address.
	 *
	 * <p>This is a JNI method that invokes the native {@code free()} function.
	 * The pointer should have been obtained from a previous call to {@link Malloc#apply(int)}.</p>
	 *
	 * <p><strong>Warning:</strong> Calling this method with an invalid pointer or
	 * calling it twice with the same pointer results in undefined behavior.</p>
	 *
	 * @param val The native memory address to free (as returned by {@link Malloc#apply(int)})
	 */
	public native void apply(long val);
}
