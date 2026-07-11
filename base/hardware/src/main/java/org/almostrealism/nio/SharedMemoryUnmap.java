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
 * Runtime-compiled JNI operation that unmaps a shared-memory region via {@code munmap}. Compiled at
 * runtime by {@link NativeCompiler}, so it needs no prebuilt native library.
 *
 * @see NIO
 */
class SharedMemoryUnmap extends BaseNative {
	/**
	 * Compiles and loads the native operation.
	 *
	 * @param compiler the compiler used to build the operation
	 */
	SharedMemoryUnmap(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
		return "#include <sys/mman.h>\n" +
				"JNIEXPORT void JNICALL " + getFunctionName() +
				" (JNIEnv* env, jobject thisObject, jobject buffer, jint length) {\n" +
				"\tvoid* sharedMemory = (*env)->GetDirectBufferAddress(env, buffer);\n" +
				"\tif (sharedMemory) munmap(sharedMemory, length);\n" +
				"}\n";
	}

	/**
	 * Unmaps a shared-memory region previously mapped by {@link SharedMemoryMap}.
	 *
	 * @param buffer the mapped {@link ByteBuffer}
	 * @param length the number of bytes to unmap
	 */
	public native void apply(ByteBuffer buffer, int length);
}
