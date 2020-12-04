package org.almostrealism.relation;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Variable;

import java.util.function.BiFunction;

public interface NameProvider {
	String getFunctionName();

	default String getDefaultAnnotation() { return null; }

	default Argument getArgument(int index) {
		return new Argument(getArgumentName(index), getDefaultAnnotation(), Double.class);
	}

	default Variable getVariable(int index) {
		return new Variable(getVariableName(index), getDefaultAnnotation(), Double.class, null);
	}

	Variable getOutputVariable();

	default String getArgumentName(int index) {
		if (getFunctionName() == null) throw new UnsupportedOperationException();
		return getFunctionName() + "_v" + index;
	}

	default String getVariableName(int index) {
		return getFunctionName() + "_l" + index;
	}

	default String getArgumentValueName(int index, int pos) {
		return getArgumentValueName(index, pos, 0);
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

	default String getArgumentValueName(int index, String pos) {
		return getArgumentValueName(index, pos, 0);
	}

	default String getArgumentValueName(int index, String pos, boolean assignment) {
		return getArgumentValueName(index, pos, assignment, 0);
	}

	default String getArgumentValueName(int index, String pos, int kernelIndex) {
		return getArgumentValueName(index, pos, true, kernelIndex);
	}

	default String getArgumentValueName(int index, String pos, boolean assignment, int kernelIndex) {
		return getVariableValueName(getArgument(index), pos, assignment, kernelIndex);
	}

	default String getVariableValueName(Variable v, String pos) {
		return getVariableValueName(v, pos, 0);
	}

	default String getVariableValueName(Variable v, String pos, boolean assignment) {
		return getVariableValueName(v, pos, assignment, 0);
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
