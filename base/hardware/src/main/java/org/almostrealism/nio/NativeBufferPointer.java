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

import java.nio.Buffer;

/**
 * Runtime-compiled JNI operation returning the native address of a direct {@link Buffer}.
 *
 * <p>Wraps {@code GetDirectBufferAddress}. Compiled at runtime by {@link NativeCompiler}, so it needs
 * no prebuilt native library and works on any platform the native backend supports.</p>
 *
 * @see NIO
 */
class NativeBufferPointer extends BaseNative {
	/**
	 * Compiles and loads the native operation.
	 *
	 * @param compiler the compiler used to build the operation
	 */
	NativeBufferPointer(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
		return "JNIEXPORT jlong JNICALL " + getFunctionName() +
				" (JNIEnv* env, jobject thisObject, jobject buffer) {\n" +
				"\treturn (jlong) (*env)->GetDirectBufferAddress(env, buffer);\n" +
				"}\n";
	}

	/**
	 * Returns the native memory address backing the given direct buffer.
	 *
	 * @param buffer a direct {@link Buffer}
	 * @return the native pointer to the buffer's backing memory
	 */
	public native long apply(Buffer buffer);
}
