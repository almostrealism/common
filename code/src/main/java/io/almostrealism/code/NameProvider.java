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

package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

public interface NameProvider {

	String getFunctionName();

	default String getVariablePrefix() { return getFunctionName(); }

	default PhysicalScope getDefaultPhysicalScope() { return null; }

	/**
	 * Specifying the size is preferred, see {@link #getArgument(LanguageOperations, int, int)}.
	 */
	@Deprecated
	default ArrayVariable getArgument(LanguageOperations lang, int index) {
		return getArgument(lang, index, null);
	}

	default Variable getVariable(int index) {
		return new Variable<>(getVariableName(index), getDefaultPhysicalScope(), Double.class, null);
	}

	default ArrayVariable getArgument(LanguageOperations lang, int index, int size) {
		return getArgument(lang, index, new IntegerConstant(size));
	}

	default ArrayVariable getArgument(LanguageOperations lang, int index, Expression<Integer> size) {
		ArrayVariable v = new ArrayVariable(lang, this, getArgumentName(index), getDefaultPhysicalScope(), Double.class, null);
		v.setArraySize(size);
		return v;
	}

	Variable getOutputVariable();

	default String getArgumentName(int index) {
		if (getVariablePrefix() == null) throw new UnsupportedOperationException();
		return getVariablePrefix() + "_v" + index;
	}

	default String getVariableName(int index) {
		return getVariablePrefix() + "_l" + index;
	}

	String getVariableDimName(ArrayVariable v, int dim);

	String getVariableSizeName(ArrayVariable v);

	Expression<?> getArrayPosition(LanguageOperations lang, ArrayVariable v, Expression<?> pos, int kernelIndex);
}
