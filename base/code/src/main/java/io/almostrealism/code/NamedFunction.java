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

/**
 * {@link NamedFunction} is an interface for objects that have an associated function name.
 * This is typically used for computations and operations that will be compiled into
 * named functions in generated code.
 *
 * <p>The function name serves as an identifier in the generated code and is used for:
 * <ul>
 *   <li>Function declarations in generated source code</li>
 *   <li>Debugging and logging to identify operations</li>
 *   <li>Metadata association for profiling</li>
 *   <li>Code organization and lookup</li>
 * </ul>
 *
 * <p>Implementations typically generate a default function name based on the class name
 * if one is not explicitly set.
 *
 * @author Michael Murray
 * @see ComputableBase
 * @see OperationAdapter#functionName(Class)
 */
public interface NamedFunction {

	/**
	 * Sets the function name for this object.
	 * The name should be a valid identifier in the target language (e.g., C, OpenCL).
	 *
	 * @param name the function name to set
	 */
	void setFunctionName(String name);

	/**
	 * Returns the function name for this object.
	 *
	 * @return the function name, or {@code null} if not set
	 */
	String getFunctionName();

	/**
	 * Returns the prefix used for generating local variable names.
	 *
	 * <p>The prefix is derived from the function name; if the function name contains
	 * underscores, the suffix from the last underscore onward is used. Since function
	 * names are globally unique, this prefix namespaces variables to this function.
	 *
	 * @return the variable prefix string
	 */
	default String getVariablePrefix() {
		String f = getFunctionName();
		if (f.contains("_")) f = f.substring(f.lastIndexOf("_"));
		return f;
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
