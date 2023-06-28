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
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

public interface NameProvider {
	boolean enableFunctionPrefix = false;

	String getFunctionName();

	default String getVariablePrefix() { return enableFunctionPrefix ? getFunctionName() : ""; }

	default PhysicalScope getDefaultPhysicalScope() { return null; }

	/**
	 * Specifying the size is preferred, see {@link #getArgument(int, int)}.
	 */
	@Deprecated
	default ArrayVariable getArgument(int index) {
		return getArgument(index, null);
	}

	default Variable getVariable(int index) {
		return new Variable<>(getVariableName(index), getDefaultPhysicalScope(), Double.class, null);
	}

	default ArrayVariable getArgument(int index, int size) {
		return getArgument(index, new Expression<>(Integer.class, String.valueOf(size)));
	}

	default ArrayVariable getArgument(int index, Expression<Integer> size) {
		ArrayVariable v = new ArrayVariable(this, getArgumentName(index), getDefaultPhysicalScope(), Double.class, null);
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

	default String getArgumentValueName(int index, int pos, boolean assignment) {
		return getArgumentValueName(index, pos, assignment, 0);
	}

	default String getArgumentValueName(int index, int pos, boolean assignment, int kernelIndex) {
		return getVariableValueName(getArgument(index), pos, assignment, kernelIndex);
	}

	default String getVariableValueName(Variable v, int pos) {
		return getVariableValueName(v, pos, 0);
	}

	default String getVariableValueName(Variable v, int pos, boolean assignment) {
		return getVariableValueName(v, pos, assignment, 0);
	}

	default String getVariableValueName(Variable v, int pos, int kernelIndex) {
		return getVariableValueName(v, pos, false, kernelIndex);
	}

	default String getVariableValueName(Variable v, String pos, int kernelIndex) {
		return getVariableValueName(v, pos, false, kernelIndex);
	}

	default String getVariableValueName(Variable v, int pos, boolean assignment, int kernelIndex) {
		return getVariableValueName(v, String.valueOf(pos), assignment, kernelIndex);
	}

	String getVariableValueName(Variable v, String pos, boolean assignment, int kernelIndex);

	String getVariableDimName(ArrayVariable v, int dim);

	String getVariableSizeName(ArrayVariable v);

	Expression<?> getArrayPosition(ArrayVariable v, Expression<?> pos, int kernelIndex);
}
