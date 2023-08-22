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

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.PrintWriter;

import java.util.List;

public class CJNIPrintWriter extends CPrintWriter {

	public CJNIPrintWriter(PrintWriter p, String topLevelMethodName) {
		this(p, topLevelMethodName, false);
	}

	public CJNIPrintWriter(PrintWriter p, String topLevelMethodName, boolean verbose) {
		super(p, topLevelMethodName, verbose);
		language = new CJNILanguageOperations();
		setExternalScopePrefix("JNIEXPORT void JNICALL");
		setEnableArgumentValueReads(true);
		setEnableArgumentValueWrites(true);
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
