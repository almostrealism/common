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
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeCompiler;

public class NativeRead extends BaseNative {
	public NativeRead(NativeCompiler compiler) {
		super(compiler);
		initNative();
	}

	@Override
	public String getFunctionDefinition() {
		if (getNativeCompiler().getPrecision() == Precision.FP64) {
			return "JNIEXPORT jdoubleArray JNICALL " + getFunctionName() +
					" (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jint len) {\n" +
					(enableVerbose ? "\tprintf(\"nativeRead(%lu) - %i values\\n\", arg, len);\n" : "") +
					"\tdouble* input = (double *) arg;\n" +
					"\tjdoubleArray output = (*env)->NewDoubleArray(env, (jsize) len);\n" +
					"\tfor (int i = 0; i < len; i++) {\n" +
					"\t\t(*env)->SetDoubleArrayRegion(env, output, i, 1, (const jdouble*)&input[offset + i]);\n" +
					"\t}\n" +
					"return output;\n" +
					"}\n";
		} else {
			return "JNIEXPORT jdoubleArray JNICALL " + getFunctionName() +
					" (JNIEnv* env, jobject thisObject, jlong arg, jint offset, jint len) {\n" +
					(enableVerbose ? "\tprintf(\"nativeRead(%lu) - %i values\\n\", arg, len);\n" : "") +
					"\tfloat* input = (float *) arg;\n" +
					"\tjdoubleArray output = (*env)->NewDoubleArray(env, (jsize) len);\n" +
					"\tfor (int i = 0; i < len; i++) {\n" +
					"\t\tjdouble value = (jdouble) input[offset + i];\n" +
					"\t\t(*env)->SetDoubleArrayRegion(env, output, i, 1, &value);\n" +
					"\t}" +
					"return output;\n" +
					"}\n";
		}
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
		if (mem.getSize() < (offset + length) * getNativeCompiler().getPrecision().bytes()) {
			throw new IllegalArgumentException("Attempt to read memory beyond the size of " + mem);
		} else if (target.length < toffset + length) {
			throw new IllegalArgumentException("Attempt to read memory into a destination array beyond the array size");
		}

		double out[] = apply(mem.getContentPointer(), offset, length);
		if (length >= 0) System.arraycopy(out, 0, target, toffset, length);
	}

	public native double[] apply(long arg, int offset, int length);
}
