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

package io.almostrealism.code;

import io.almostrealism.scope.Variable;

public interface NameProvider {

	String getFunctionName();

	default String getVariablePrefix() {
		String f = getFunctionName();
		if (f.contains("_")) f = f.substring(f.lastIndexOf("_"));
		return f;
	}

	default String getArgumentName(int index) {
		if (getVariablePrefix() == null) throw new UnsupportedOperationException();
		return getVariablePrefix() + "_v" + index;
	}

	default String getVariableName(int index) {
		return getVariablePrefix() + "_l" + index;
	}

	Variable getOutputVariable();
}
