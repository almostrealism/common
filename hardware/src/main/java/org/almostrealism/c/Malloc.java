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

public class Malloc extends BaseNative {

	public Malloc(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
		return "JNIEXPORT jlong JNICALL " + getFunctionName() + " (JNIEnv* env, jobject thisObject, jint len) {\n" +
				(enableVerbose ? "\tprintf(\"malloc - %i bytes\\n\", len);\n" : "") +
				"\treturn (jlong) malloc((size_t) len);\n" +
				"}\n";
	}

	public native long apply(int len);
}
