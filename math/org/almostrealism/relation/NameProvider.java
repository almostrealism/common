package org.almostrealism.relation;

import io.almostrealism.code.Argument;

public interface NameProvider {
	String getFunctionName();

	default String getArgumentName(int index) {
		return getFunctionName() + "_v" + index;
	}

	default String getVariableName(int index) {
		return getFunctionName() + "_l" + index;
	}

	default String getArgumentValueName(int index, int pos) {
		return getArgumentValueName(index, pos, 0);
	}

	default String getArgumentValueName(int index, int pos, int kernelIndex) {
		return getArgumentValueName(getArgumentName(index), pos, kernelIndex);
	}

	default String getArgumentValueName(Argument arg, int pos) {
		return getArgumentValueName(arg.getName(), pos, 0);
	}

	default String getArgumentValueName(Argument arg, int pos, int kernelIndex) {
		return getArgumentValueName(arg.getName(), pos, kernelIndex);
	}

	default String getArgumentValueName(String v, int pos, int kernelIndex) {
		return getArgumentValueName(v, pos, true, kernelIndex);
	}

	default String getArgumentValueName(String v, int pos, boolean assignment) {
		return getArgumentValueName(v, pos, assignment, 0);
	}

	String getArgumentValueName(String v, int pos, boolean assignment, int kernelIndex);
}
