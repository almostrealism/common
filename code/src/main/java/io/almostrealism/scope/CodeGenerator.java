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

package io.almostrealism.scope;

import io.almostrealism.lang.LanguageOperations;

/**
 * A strategy interface for generating source code from a {@link Scope}.
 *
 * <p>Implementations of this interface are responsible for transforming
 * the abstract representation of a computation (represented by a {@link Scope})
 * into executable source code in a target language. The actual language
 * syntax and semantics are provided by the {@link LanguageOperations} parameter.</p>
 *
 * <p>This interface enables different code generation strategies to be
 * plugged in, supporting various output formats such as:</p>
 * <ul>
 *   <li>C/C++ for native compilation</li>
 *   <li>OpenCL for GPU execution</li>
 *   <li>Metal shading language for Apple GPUs</li>
 *   <li>Other target languages as needed</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * CodeGenerator generator = new MyCodeGenerator();
 * LanguageOperations cLang = new CLanguageOperations();
 * String sourceCode = generator.generate(scope, cLang);
 * }</pre>
 *
 * @see Scope
 * @see LanguageOperations
 *
 * @author Michael Murray
 */
public interface CodeGenerator {

	/**
	 * Generates source code from the given scope using the specified
	 * language operations.
	 *
	 * <p>The generated code should be a complete, compilable unit
	 * appropriate for the target language, including any necessary
	 * declarations, function definitions, and statements derived
	 * from the scope's contents.</p>
	 *
	 * @param scope the scope containing the computation to generate code for,
	 *              including variables, expressions, and nested scopes
	 * @param lang  the language operations that define syntax and semantics
	 *              for the target language
	 * @return the generated source code as a string, ready for compilation
	 *         or further processing
	 */
	String generate(Scope<?> scope, LanguageOperations lang);
}
