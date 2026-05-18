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

package io.almostrealism.code;

/**
 * Provides naming conventions for functions, arguments, and local variables during code generation.
 *
 * <p>Implementations of {@code NameProvider} are used during scope compilation to ensure
 * that generated variable names are unique and follow a consistent naming scheme derived
 * from the function name. Argument names use the suffix {@code _v<index>} and local variable
 * names use {@code _l<index>}.</p>
 *
 * @see io.almostrealism.scope.Scope
 * @see io.almostrealism.code.ScopeInputManager
 */
public interface NameProvider {

	/**
	 * Returns the function name associated with this provider.
	 *
	 * @return the function name
	 */
	String getFunctionName();

	/**
	 * Returns the prefix used for generating argument and variable names.
	 *
	 * <p>The prefix is derived from the function name; if the function name contains
	 * underscores, the suffix after the last underscore is used.
	 *
	 * @return the variable prefix string
	 */
	default String getVariablePrefix() {
		String f = getFunctionName();
		if (f.contains("_")) f = f.substring(f.lastIndexOf("_"));
		return f;
	}

	/**
	 * Returns the name for the argument at the specified index.
	 *
	 * @param index the zero-based argument index
	 * @return the argument name (e.g., {@code prefix_v0})
	 * @throws UnsupportedOperationException if the variable prefix is {@code null}
	 */
	default String getArgumentName(int index) {
		if (getVariablePrefix() == null)
			throw new UnsupportedOperationException();

		return getVariablePrefix() + "_v" + index;
	}

	/**
	 * Returns the name for the local variable at the specified index.
	 *
	 * @param index the zero-based variable index
	 * @return the local variable name (e.g., {@code prefix_l0})
	 * @throws UnsupportedOperationException if the variable prefix is {@code null}
	 */
	default String getVariableName(int index) {
		if (getVariablePrefix() == null)
			throw new UnsupportedOperationException();

		return getVariablePrefix() + "_l" + index;
	}
}
