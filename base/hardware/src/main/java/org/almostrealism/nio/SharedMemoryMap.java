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
 * Runtime-compiled JNI operation that maps a named file into memory and returns a direct
 * {@link ByteBuffer} over the mapping.
 *
 * <p>Wraps POSIX {@code open}/{@code ftruncate}/{@code mmap} plus {@code NewDirectByteBuffer}. The
 * required POSIX headers are pulled in by the generated source. Compiled at runtime by
 * {@link NativeCompiler}, so it needs no prebuilt native library.</p>
 *
 * @see NativeMemoryProvider
 */
class SharedMemoryMap extends BaseNative {
	/**
	 * Compiles and loads the native operation.
	 *
	 * @param compiler the compiler used to build the operation
	 */
	SharedMemoryMap(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
		return "#include <fcntl.h>\n" +
				"#include <unistd.h>\n" +
				"#include <sys/mman.h>\n" +
				"JNIEXPORT jobject JNICALL " + getFunctionName() +
				" (JNIEnv* env, jobject thisObject, jstring jFilePath, jint length) {\n" +
				"\tconst char* filePath = (*env)->GetStringUTFChars(env, jFilePath, NULL);\n" +
				"\tif (!filePath) return NULL;\n" +
				"\tint fd = open(filePath, O_CREAT | O_RDWR, 0666);\n" +
				"\tif (fd == -1) { (*env)->ReleaseStringUTFChars(env, jFilePath, filePath); return NULL; }\n" +
				"\tif (ftruncate(fd, length) == -1) {\n" +
				"\t\tclose(fd); (*env)->ReleaseStringUTFChars(env, jFilePath, filePath); return NULL;\n" +
				"\t}\n" +
				"\tvoid* sharedMemory = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);\n" +
				"\tclose(fd);\n" +
				"\t(*env)->ReleaseStringUTFChars(env, jFilePath, filePath);\n" +
				"\tif (sharedMemory == MAP_FAILED) return NULL;\n" +
				"\treturn (*env)->NewDirectByteBuffer(env, sharedMemory, length);\n" +
				"}\n";
	}

	/**
	 * Maps the named file into memory and returns a direct buffer over the region.
	 *
	 * @param filePath the shared-memory file path
	 * @param length   the number of bytes to map
	 * @return a direct {@link ByteBuffer} backed by the mapping, or {@code null} on failure
	 */
	public native ByteBuffer apply(String filePath, int length);
}
