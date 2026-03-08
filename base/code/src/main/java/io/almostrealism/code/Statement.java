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

package io.almostrealism.code;

import io.almostrealism.kernel.KernelStructure;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Fragment;
import io.almostrealism.scope.Variable;

import java.util.List;

/**
 * Represents a code statement that can be rendered into target language code.
 * <p>
 * This interface combines {@link Fragment} for code generation and {@link KernelStructure}
 * for kernel-aware processing. Statements are the building blocks of generated code,
 * representing individual executable units such as variable declarations, assignments,
 * function calls, or control flow constructs.
 * </p>
 * <p>
 * Implementations must provide:
 * <ul>
 *   <li>A method to generate the statement text for a target language</li>
 *   <li>A list of variable dependencies for dependency analysis</li>
 *   <li>Simplification support (inherited from KernelStructure)</li>
 * </ul>
 * </p>
 *
 * <h2>Implementation Examples</h2>
 * <ul>
 *   <li>{@link io.almostrealism.expression.ArrayDeclaration} - array variable declarations</li>
 *   <li>Assignment statements</li>
 *   <li>Control flow statements (if, for, while)</li>
 * </ul>
 *
 * @param <T> the specific statement type (for self-referential simplification)
 * @see Fragment
 * @see KernelStructure
 * @see Variable
 */
public interface Statement<T extends KernelStructure> extends Fragment, KernelStructure<T> {
	/**
	 * Generates the statement code for the target language.
	 * <p>
	 * The returned string should be a complete, valid statement in the target
	 * language, including any necessary syntax (e.g., semicolons in C).
	 * </p>
	 *
	 * @param lang the language operations for the target language
	 * @return the statement code as a string
	 */
	String getStatement(LanguageOperations lang);

	/**
	 * Returns the list of variables that this statement depends on.
	 * <p>
	 * This information is used for dependency analysis, ordering statements,
	 * and determining which variables must be defined before this statement
	 * can be executed.
	 * </p>
	 *
	 * @return a list of variable dependencies, may be empty but not null
	 */
	List<Variable<?, ?>> getDependencies();
}
