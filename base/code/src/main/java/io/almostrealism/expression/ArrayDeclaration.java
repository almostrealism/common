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

package io.almostrealism.expression;

import io.almostrealism.code.Statement;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;

import java.util.List;

/**
 * Represents a declaration of an array variable in generated code.
 * <p>
 * This class is a {@link Statement} that generates array declaration syntax
 * for various target languages. It captures the element type, array name,
 * and size expression needed to declare a fixed-size array.
 * </p>
 * <p>
 * The actual declaration syntax is delegated to the {@link LanguageOperations}
 * instance, allowing this class to work across different target languages
 * (C, OpenCL, etc.).
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Expression<?> sizeExpr = new IntegerConstant(100);
 * ArrayDeclaration<Double> decl = new ArrayDeclaration<>(Double.class, "buffer", sizeExpr);
 * String code = decl.getStatement(lang);
 * // Generates something like: double buffer[100];
 * }</pre>
 *
 * @param <T> the element type of the array being declared
 * @see Statement
 * @see LanguageOperations#declaration(Class, String, String, String)
 */
public class ArrayDeclaration<T> implements Statement<ArrayDeclaration<T>> {
	/** The element type of the array. */
	private Class<T> type;
	/** The name of the array variable. */
	private String name;
	/** The expression representing the size of the array. */
	private Expression<?> size;

	/**
	 * Constructs a new array declaration.
	 *
	 * @param type the Java class representing the array element type
	 * @param name the name for the array variable
	 * @param size the expression representing the array size
	 */
	public ArrayDeclaration(Class<T> type, String name, Expression<?> size) {
		this.type = type;
		this.name = name;
		this.size = size;
	}

	/**
	 * Returns the element type of the array.
	 *
	 * @return the Java class representing the array element type
	 */
	public Class<T> getType() {
		return type;
	}

	/**
	 * Returns the name of the array variable.
	 *
	 * @return the array variable name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the size expression for the array.
	 *
	 * @return the expression representing the array size
	 */
	public Expression<?> getSize() {
		return size;
	}

	/**
	 * Generates the array declaration statement for the target language.
	 * <p>
	 * Delegates to the language operations to produce the appropriate
	 * declaration syntax (e.g., "double buffer[100];" for C).
	 * </p>
	 *
	 * @param lang the language operations for the target language
	 * @return the array declaration statement as a string
	 */
	@Override
	public String getStatement(LanguageOperations lang) {
		return lang.declaration(type, name, null, size.getExpression(lang));
	}

	/**
	 * Returns the variables that this declaration depends on.
	 * <p>
	 * The dependencies are determined by the size expression, since
	 * the array size may be computed from other variables.
	 * </p>
	 *
	 * @return the list of variable dependencies from the size expression
	 */
	@Override
	public List<Variable<?, ?>> getDependencies() {
		return size.getDependencies();
	}

	/**
	 * Creates a simplified version of this array declaration.
	 * <p>
	 * Simplifies the size expression while preserving the type and name.
	 * </p>
	 *
	 * @param context the kernel structure context for simplification
	 * @param depth   the current simplification depth
	 * @return a new ArrayDeclaration with a simplified size expression
	 */
	@Override
	public ArrayDeclaration<T> simplify(KernelStructureContext context, int depth) {
		return new ArrayDeclaration<>(type, name, size.simplify(context, depth + 1));
	}
}
