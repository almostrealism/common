/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.jni;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.io.PrintWriter;

import java.util.List;

public class CJNIPrintWriter extends CPrintWriter {
	private JNIMemoryAccessor accessor;
	private int parallel;

	public CJNIPrintWriter(PrintWriter p, String topLevelMethodName, int parallelism,
						   LanguageOperations lang, JNIMemoryAccessor memAccess) {
		this(p, topLevelMethodName, parallelism, lang, memAccess, false);
	}

	public CJNIPrintWriter(PrintWriter p, String topLevelMethodName, int parallelism,
						   LanguageOperations lang, JNIMemoryAccessor memAccess, boolean verbose) {
		super(p, topLevelMethodName, lang.getPrecision(), verbose);
		parallel = parallelism;
		language = lang;
		accessor = memAccess;
		setExternalScopePrefix("JNIEXPORT void JNICALL");
		setEnableArgumentValueReads(true);
		setEnableArgumentValueWrites(true);
	}

	@Override
	public void beginScope(String name, OperationMetadata metadata, Accessibility access, List<ArrayVariable<?>> arguments, List<Variable<?, ?>> parameters) {
		super.beginScope(name, metadata, access, arguments, parameters);

		if (access == Accessibility.EXTERNAL) {
			if (parallel > 1) {
				println("for (int global_id = global_index ; global_id < global_total; global_id += " + parallel + ") {");
			}
		}
	}

	@Override
	public void endScope() {
		if (isExternalScope() && parallel > 1) {
			println("}");
		}

		super.endScope();
	}

	protected void renderArgumentReads(List<ArrayVariable<?>> arguments) {
		println(new ExpressionAssignment<long[]>(true,
				new StaticReference(long[].class, "*argArr"),
				new StaticReference<>(long[].class, "(*env)->GetLongArrayElements(env, arg, 0)")));
		println(new ExpressionAssignment<int[]>(true,
				new StaticReference(int[].class, "*offsetArr"),
				new StaticReference<>(int[].class, "(*env)->GetIntArrayElements(env, offset, 0)")));
		println(new ExpressionAssignment<int[]>(true,
				new StaticReference(int[].class, "*sizeArr"),
				new StaticReference<>(int[].class, "(*env)->GetIntArrayElements(env, size, 0)")));

		super.renderArgumentReads(arguments);
	}

	@Override
	protected void copyInline(int index, ArrayVariable<?> variable, boolean write) {
		String access = accessor.copyInline(getLanguage(), index, variable, write);
		if (access != null) println(access);
	}

	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		super.renderArgumentWrites(arguments);

		println("(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);");
		println("(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);");
		println("(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);");
	}
}
