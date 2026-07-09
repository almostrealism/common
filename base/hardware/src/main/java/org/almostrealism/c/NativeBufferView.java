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

package org.almostrealism.c;

import org.almostrealism.hardware.jni.NativeCompiler;

import java.nio.ByteBuffer;

/**
 * JNI wrapper that exposes a range of native memory as a direct {@link ByteBuffer}.
 *
 * <p>{@link NativeBufferView} provides a native operation that wraps a region of memory
 * allocated via {@link Malloc} in a direct buffer using {@code NewDirectByteBuffer},
 * without copying. This allows bulk transfer APIs that accept NIO buffers (such as
 * OpenCL's buffer read and write operations) to move data directly between native
 * memory and a device, avoiding the per-element array mediation performed by
 * {@link NativeRead} and {@link NativeWrite}.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * NativeBufferView view = new NativeBufferView(compiler);
 *
 * // View 1024 bytes starting 256 bytes into the allocation
 * ByteBuffer buffer = view.apply(mem.getContentPointer(), 256, 1024);
 * }</pre>
 *
 * <h2>Lifetime</h2>
 *
 * <p>The returned buffer is a view, not a copy: it remains valid only while the
 * underlying allocation is live. Callers must keep the owning {@link NativeMemory}
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
