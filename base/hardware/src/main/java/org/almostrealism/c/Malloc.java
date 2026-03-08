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

package org.almostrealism.c;

import org.almostrealism.hardware.jni.NativeCompiler;

/**
 * JNI wrapper for allocating native memory via C malloc.
 *
 * <p>{@link Malloc} provides a native {@code malloc()} operation that allocates
 * memory outside the Java heap. The memory is managed manually and must be
 * released via {@link Free#apply(long)}.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * // Allocate native memory
 * Malloc malloc = new Malloc(compiler);
 * long ptr = malloc.apply(1024);  // Allocate 1024 bytes
 *
 * // Use memory via NativeWrite, NativeRead, or JNI operations...
 *
 * // Free native memory when done
 * Free free = new Free(compiler);
 * free.apply(ptr);
 * }</pre>
 *
 * <h2>Memory Management</h2>
 *
 * <p>Memory allocated by {@link Malloc} is not garbage collected. Failure to
 * call {@link Free#apply(long)} results in memory leaks. For automatic memory
 * management, consider using {@link NativeMemoryProvider} instead.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>The native {@code malloc()} function is thread-safe. Multiple threads
 * can allocate memory concurrently.</p>
 *
 * @see Free
 * @see NativeMemoryProvider
 * @see BaseNative
 */
public class Malloc extends BaseNative {

	/**
	 * Creates a new Malloc operation using the specified compiler.
	 *
	 * <p>Compiles and loads the native library immediately upon construction.</p>
	 *
	 * @param compiler The {@link NativeCompiler} for compiling the C code
	 */
	public Malloc(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	/**
	 * Returns the C function definition for the native malloc operation.
	 *
	 * <p>Generates a JNI-compatible C function that allocates zero-initialized memory
	 * using the standard C {@code calloc()} function and returns the pointer as a {@code jlong}.</p>
	 *
	 * @return The C code defining the JNI malloc function
	 */
	@Override
	public String getFunctionDefinition() {
		return "JNIEXPORT jlong JNICALL " + getFunctionName() + " (JNIEnv* env, jobject thisObject, jint len) {\n" +
				(enableVerbose ? "\tprintf(\"malloc - %i bytes\\n\", len);\n" : "") +
				"\treturn (jlong) calloc((size_t) len, 1);\n" +
				"}\n";
	}

	/**
	 * Allocates native memory of the specified size.
	 *
	 * <p>This is a JNI method that invokes the native {@code malloc()} function.
	 * The returned pointer must eventually be freed using {@link Free#apply(long)}
	 * to prevent memory leaks.</p>
	 *
	 * @param len The number of bytes to allocate
	 * @return The native memory address of the allocated block, or 0 if allocation fails
	 */
	public native long apply(int len);
}
