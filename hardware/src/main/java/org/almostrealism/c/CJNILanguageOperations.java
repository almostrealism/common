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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class CJNILanguageOperations extends CLanguageOperations {
	private List<String> libraryMethods;

	public CJNILanguageOperations(Precision precision) {
		super(precision, true, true);
		this.libraryMethods = new ArrayList<>();
	}

	protected List<String> getLibraryMethods() { return libraryMethods; }

	@Override
	public String pow(String a, String b) {
		if (getPrecision() != Precision.FP64) {
			return "powf(" + a + ", " + b + ")";
		}

		return super.pow(a, b);
	}

	@Override
	public String min(String a, String b) {
		return "f" + super.min(a, b);
	}

	@Override
	public String max(String a, String b) {
		return "f" + super.max(a, b);
	}

	@Override
	public String nameForType(Class<?> type) {
		if (type == Integer.class || type == int[].class) {
			return "jint";
		} else if (type == Long.class || type == long[].class) {
			return "jlong";
		} else {
			return super.nameForType(type);
		}
	}

	@Override
	public void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (access == Accessibility.EXTERNAL) {
			out.accept("JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jint count, jint global_index, jlong global_total");
			return;
		}

		super.renderArguments(arguments, out, access);
	}

	@Override
	protected void renderParameters(List<Variable<?, ?>> arguments, Consumer<String> out, boolean enableType, boolean enableAnnotation, Accessibility access, Class replaceType, String prefix, String suffix) {
		super.renderParameters(arguments, out, enableType, enableAnnotation, access, replaceType, prefix, suffix);
		out.accept(", ");
		out.accept("jint global_id");
	}

	@Override
	protected void renderParameters(String methodName, List<Expression> parameters, Consumer<String> out) {
		super.renderParameters(methodName, parameters, out);
		if (!getLibraryMethods().contains(methodName)) out.accept(", global_id");
	}
}
