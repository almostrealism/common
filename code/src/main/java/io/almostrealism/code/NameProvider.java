/*
 * Copyright 2020 Michael Murray
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

public interface NameProvider {
	String getFunctionName();

	default String getDefaultAnnotation() { return null; }

	default ArrayVariable getArgument(int index) {
		return new ArrayVariable(this, getArgumentName(index), getDefaultAnnotation(), Double.class, null);
	}

	default Variable getVariable(int index) {
		return new Variable<>(getVariableName(index), getDefaultAnnotation(), Double.class, null);
	}

	Variable getOutputVariable();

	default String getArgumentName(int index) {
		if (getFunctionName() == null) throw new UnsupportedOperationException();
		return getFunctionName() + "_v" + index;
	}

	default String getVariableName(int index) {
		return getFunctionName() + "_l" + index;
	}

	default String getArgumentValueName(int index, int pos, boolean assignment) {
		return getArgumentValueName(index, pos, assignment, 0);
	}

	default String getArgumentValueName(int index, int pos, int kernelIndex) {
		return getArgumentValueName(index, pos, true, kernelIndex);
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

	default String getVariableValueName(Variable v, String pos) {
		return getVariableValueName(v, pos, 0);
	}

	default String getVariableValueName(Variable v, String pos, int kernelIndex) {
		return getVariableValueName(v, pos, false, kernelIndex);
	}

	default String getVariableValueName(Variable v, int pos, boolean assignment, int kernelIndex) {
		return getVariableValueName(v, String.valueOf(pos), assignment, kernelIndex);
	}

	String getVariableValueName(Variable v, String pos, boolean assignment, int kernelIndex);

	default NameProvider withOutputVariable(Variable outputVariable) {
		NameProvider p = this;

		return new NameProvider() {
			@Override
			public String getFunctionName() {
				return p.getFunctionName();
			}

			@Override
			public Variable getOutputVariable() {
				return outputVariable;
			}

			@Override
			public String getVariableValueName(Variable v, String pos, boolean assignment, int kernelIndex) {
				return p.getVariableValueName(v, pos, assignment, kernelIndex);
			}
		};
	}
}
