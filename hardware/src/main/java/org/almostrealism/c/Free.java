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

public class Free extends BaseNative {

	public Free(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

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

	public native void apply(long val);
}
