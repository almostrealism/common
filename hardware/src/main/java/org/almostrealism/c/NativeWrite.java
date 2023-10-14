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

import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeCompiler;

public class NativeWrite extends BaseNative {
	public NativeWrite(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
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
	}

	public double[] apply(MemoryData mem) {
		double out[] = new double[mem.getMemLength()];
		apply((NativeMemory) mem.getMem(), out);
		return out;
	}

	public void apply(NativeMemory mem, double target[]) { apply(mem, 0, target); }

	public void apply(NativeMemory mem, int offset, double target[]) {
		apply(mem, offset, target, 0, target.length);
	}

	public void apply(NativeMemory mem, int offset, double target[], int toffset, int length) {
		apply(mem.getNativePointer(), offset, target, toffset, length);
	}

	public native void apply(long arg, int offset, double[] target, int toffset, int length);
}
