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
import io.almostrealism.code.Precision;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;

/** The LanguageOperations interface. */
public interface LanguageOperations {

	/** Performs the getPrecision operation. */
	Precision getPrecision();

	default boolean isInt64() {
		return getPrecision() == Precision.FP64;
	}

	/** Performs the stringForLong operation. */
	default String stringForLong(long value) {
		if (isInt64()) {
			return Precision.FP64.stringForLong(value);
		}

		return getPrecision().stringForLong(value);
	}

	/** Performs the pi operation. */
	String pi();

	/** Performs the pow operation. */
	String pow(String a, String b);

	/** Performs the min operation. */
	String min(String a, String b);
	/** Performs the max operation. */
	String max(String a, String b);

	/** Performs the abs operation. */
	String abs(String value);

	/** Performs the kernelIndex operation. */
	String kernelIndex(int axis);

	/** Performs the declaration operation. */
	default String declaration(Class type, String destination, String expression) {
		return declaration(type, destination, expression, null);
	}

	/** Performs the declaration operation. */
	String declaration(Class type, String destination, String expression, String arrayLength);

	/** Performs the assignment operation. */
	String assignment(String destination, String expression);

	/** Performs the annotationForPhysicalScope operation. */
	String annotationForPhysicalScope(Accessibility access, PhysicalScope scope);

	/** Performs the nameForType operation. */
	String nameForType(Class<?> type);

	/** Performs the renderMethod operation. */
	String renderMethod(Method<?> method);

	/** Performs the getVariableSizeName operation. */
	default String getVariableSizeName(ArrayVariable v) {
		return v.getName() + "Size";
	}

	default boolean isVariableOffsetSupported() {
		return true;
	}

	default String getStatementTerminator() {
		return "";
	}
}
