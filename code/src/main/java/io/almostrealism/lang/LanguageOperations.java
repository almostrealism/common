/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.Accessibility;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.code.Precision;
import io.almostrealism.scope.Method;

public interface LanguageOperations {

	Precision getPrecision();

	default String stringForLong(long value) {
		return getPrecision().stringForLong(value);
	}

	String pi();

	String pow(String a, String b);

	String min(String a, String b);
	String max(String a, String b);

	String kernelIndex(int axis);

	default String declaration(Class type, String destination, String expression) {
		return declaration(type, destination, expression, null);
	}

	String declaration(Class type, String destination, String expression, String arrayLength);

	String assignment(String destination, String expression);

	String annotationForPhysicalScope(Accessibility access, PhysicalScope scope);

	String nameForType(Class<?> type);

	String renderMethod(Method<?> method);

	default boolean isVariableOffsetSupported() {
		return true;
	}

	default String getStatementTerminator() {
		return "";
	}
}
