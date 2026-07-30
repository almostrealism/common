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

import java.nio.ByteBuffer;

/**
 * Runtime-compiled JNI operation that exposes a range of native memory as a direct {@link ByteBuffer}.
 *
 * <p>Wraps a region of {@link Malloc}-allocated memory in a direct buffer via
 * {@code NewDirectByteBuffer}, without copying. This lets a {@link NativeMemory} satisfy the
 * {@link org.almostrealism.hardware.mem.DirectMemory} contract — so bulk transfer APIs that
 * accept NIO buffers (such as OpenCL's buffer read and write) can move data directly between calloc
 * memory and a device.</p>
 *
 * <p>Like {@link Malloc} and {@link NativeRead}, the C source is compiled at runtime by
 * {@link NativeCompiler}, so no prebuilt native library is required and it works on any platform the
 * native backend supports.</p>
 *
 * <h2>Lifetime</h2>
 *
 * <p>The returned buffer is a non-owning view: the JVM does not free its memory, and it remains valid
 * only while the underlying allocation is live. Callers must keep the owning {@link NativeMemory}
 * strongly reachable for as long as the buffer is in use.</p>
 *
 * @see NativeMemory
 * @see NativeMemoryProvider
 * @see BaseNative
 */
public class NativeBufferView extends BaseNative {
	/**
	 * Creates a new NativeBufferView operation using the specified compiler.
	 *
	 * <p>Compiles and loads the native library immediately upon construction.</p>
	 *
	 * @param compiler The {@link NativeCompiler} for compiling the C code
	 */
	public NativeBufferView(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	/**
	 * Returns the C function definition for the native buffer view operation.
	 *
	 * <p>Generates a JNI-compatible C function that wraps a native memory range in a
	 * direct byte buffer via {@code NewDirectByteBuffer}. No data is copied.</p>
	 *
	 * @return The C code defining the JNI view function
	 */
	@Override
	public String getFunctionDefinition() {
		return "JNIEXPORT jobject JNICALL " + getFunctionName() +
				" (JNIEnv* env, jobject thisObject, jlong arg, jlong offset, jlong len) {\n" +
				"\treturn (*env)->NewDirectByteBuffer(env, (void*) (arg + offset), len);\n" +
				"}\n";
	}

	/**
	 * Native method that wraps a native memory range in a direct {@link ByteBuffer}.
	 *
	 * @param arg    The native memory address the range belongs to
	 * @param offset The starting offset within the allocation, in bytes
	 * @param length The length of the range, in bytes
	 * @return A direct buffer view of the range
	 */
	public native ByteBuffer apply(long arg, long offset, long length);
}
