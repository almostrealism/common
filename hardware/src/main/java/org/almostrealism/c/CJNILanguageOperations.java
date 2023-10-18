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
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.ArrayVariable;

import java.util.List;
import java.util.function.Consumer;

public class CJNILanguageOperations extends CLanguageOperations {
	public CJNILanguageOperations() {
		super(true, true);
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
	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (access == Accessibility.EXTERNAL) {
			out.accept("JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_id");
			return;
		}

		super.renderArguments(arguments, out, access);

		if (!arguments.isEmpty()) {
			out.accept(", ");
			out.accept("uint global_id");
		}
	}

	@Override
	protected void renderParameters(List<Expression> parameters, Consumer<String> out) {
		super.renderParameters(parameters, out);
		out.accept(", global_id");
	}
}
