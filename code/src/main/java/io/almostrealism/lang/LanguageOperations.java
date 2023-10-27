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

public interface LanguageOperations {
	Precision getPrecision();

	String pow(String a, String b);

	String min(String a, String b);
	String max(String a, String b);

	String kernelIndex(int index);

	String annotationForPhysicalScope(PhysicalScope scope);

	String nameForType(Class<?> type);

	String renderMethod(Method<?> method);
}