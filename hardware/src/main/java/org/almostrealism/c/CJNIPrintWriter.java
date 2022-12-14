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

import io.almostrealism.code.Accessibility;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.function.Consumer;

public class CJNIPrintWriter extends CPrintWriter {

	public CJNIPrintWriter(PrintWriter p, String topLevelMethodName) {
		this(p, topLevelMethodName, false);
	}

	public CJNIPrintWriter(PrintWriter p, String topLevelMethodName, boolean verbose) {
		super(p, topLevelMethodName, verbose);
		setExternalScopePrefix("JNIEXPORT void JNICALL");
		setEnableArrayVariables(true);
	}

	@Override
	public void println(Method method) {
		p.println(renderMethod(method));
	}

	@Override
	protected String nameForType(Class<?> type) {
		if (type == Integer.class || type == int[].class) {
			return "jint";
		} else if (type == Long.class || type == long[].class) {
			return "jlong";
		} else {
			return super.nameForType(type);
		}
	}

	@Override
	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (access == Accessibility.EXTERNAL) {
			out.accept("JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jint count");
		} else {
			super.renderArguments(arguments, out, access);
		}
	}

	protected void renderArgumentReads(List<ArrayVariable<?>> arguments) {
		println(new Variable<>("*argArr", long[].class, "(*env)->GetLongArrayElements(env, arg, 0)"));
		println(new Variable<>("*offsetArr", int[].class, "(*env)->GetIntArrayElements(env, offset, 0)"));
		println(new Variable<>("*sizeArr", int[].class, "(*env)->GetIntArrayElements(env, size, 0)"));

		super.renderArgumentReads(arguments);
	}

	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		super.renderArgumentWrites(arguments);

		println("free(argArr);");
		println("free(offsetArr);");
		println("free(sizeArr);");
	}
}
