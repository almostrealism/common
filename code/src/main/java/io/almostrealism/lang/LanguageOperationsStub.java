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

package io.almostrealism.lang;

import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.Precision;
import io.almostrealism.scope.Method;

public class LanguageOperationsStub implements LanguageOperations {
	@Override
	public Precision getPrecision() { return Precision.FP64; }

	@Override
	public String pow(String a, String b) {
		return "pow(" + a + ", " + b + ")";
	}

	@Override
	public String min(String a, String b) {
		return "min(" + a + ", " + b + ")";
	}

	@Override
	public String max(String a, String b) {
		return "max(" + a + ", " + b + ")";
	}

	@Override
	public String kernelIndex(int index) {
		return "kernel" + index;
	}

	@Override
	public String declaration(Class type, String destination, String expression) {
		if (type == null) {
			return assignment(destination, expression);
		}

		return nameForType(type) + " " + destination + " = " + expression;
	}

	@Override
	public String assignment(String destination, String expression) {
		return destination + " = " + expression;
	}

	@Override
	public String annotationForPhysicalScope(PhysicalScope scope) {
		return "none";
	}

	@Override
	public String nameForType(Class<?> type) {
		return type == null ? "null" : type.getSimpleName();
	}

	@Override
	public String renderMethod(Method<?> method) {
		return method.getSimpleExpression(this);
	}
}
