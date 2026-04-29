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

package io.almostrealism.lang;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;

/**
 * The interface that all target language backends must implement to participate in
 * code generation.
 *
 * <p>Defines the vocabulary of primitive operations needed to generate valid code for a
 * given language (C, OpenCL, Metal, etc.): arithmetic functions, variable declarations,
 * assignments, and kernel index access.</p>
 */
public interface LanguageOperations {

	/**
	 * Returns the floating-point precision setting active for this language backend.
	 *
	 * @return the current {@link Precision}
	 */
	Precision getPrecision();

	/**
	 * Returns {@code true} if this backend uses 64-bit integers ({@link Precision#FP64}).
	 *
	 * @return {@code true} for 64-bit integer mode
	 */
	default boolean isInt64() {
		return getPrecision() == Precision.FP64;
	}

	/**
	 * Formats a long value as a string literal appropriate for this language backend.
	 *
	 * @param value the long value to format
	 * @return the language-specific literal string
	 */
	default String stringForLong(long value) {
		if (isInt64()) {
			return Precision.FP64.stringForLong(value);
		}

		return getPrecision().stringForLong(value);
	}

	/**
	 * Returns the language-specific literal or constant for the mathematical constant pi.
	 *
	 * @return a string expression for pi
	 */
	String pi();

	/**
	 * Returns a language-specific power expression ({@code a^b}).
	 *
	 * @param a the base expression string
	 * @param b the exponent expression string
	 * @return the rendered power expression
	 */
	String pow(String a, String b);

	/**
	 * Returns an expression for the integer modulo {@code a mod b} that is
	 * guaranteed to produce a non-negative result even when {@code a} is
	 * negative. The default implementation emits {@code ((a % b) + b) % b}
	 * because C, OpenCL, and Metal all follow C99 semantics where {@code %}
	 * preserves the sign of the dividend.
	 *
	 * @param a the dividend expression
	 * @param b the divisor expression
	 * @return a language-appropriate non-negative modulo expression
	 */
	default String floorMod(String a, String b) {
		return "((" + a + " % " + b + ") + " + b + ") % " + b;
	}

	/**
	 * Returns a language-specific minimum expression.
	 *
	 * @param a the first operand expression string
	 * @param b the second operand expression string
	 * @return the rendered minimum expression
	 */
	String min(String a, String b);

	/**
	 * Returns a language-specific maximum expression.
	 *
	 * @param a the first operand expression string
	 * @param b the second operand expression string
	 * @return the rendered maximum expression
	 */
	String max(String a, String b);

	/**
	 * Returns a language-specific absolute-value expression.
	 *
	 * @param value the operand expression string
	 * @return the rendered absolute-value expression
	 */
	String abs(String value);

	/**
	 * Returns the language-specific kernel index variable for the given axis.
	 *
	 * @param axis the kernel dimension axis (0-based)
	 * @return the kernel index expression string
	 */
	String kernelIndex(int axis);

	/**
	 * Returns a variable declaration string without an array length (scalar declaration).
	 *
	 * @param type        the Java type to declare
	 * @param destination the variable name
	 * @param expression  the initialisation expression, or {@code null}
	 * @return the declaration statement
	 */
	default String declaration(Class type, String destination, String expression) {
		return declaration(type, destination, expression, null);
	}

	/**
	 * Returns a variable declaration string, optionally with an array length suffix.
	 *
	 * @param type        the Java type to declare
	 * @param destination the variable name
	 * @param expression  the initialisation expression, or {@code null}
	 * @param arrayLength the array length expression, or {@code null} for a scalar
	 * @return the declaration statement
	 */
	String declaration(Class type, String destination, String expression, String arrayLength);

	/**
	 * Returns an assignment statement string.
	 *
	 * @param destination the lvalue expression string
	 * @param expression  the rvalue expression string
	 * @return the assignment statement
	 */
	String assignment(String destination, String expression);

	/**
	 * Returns the annotation or keyword required for a variable with the given physical scope.
	 *
	 * @param access the accessibility level
	 * @param scope  the physical memory scope
	 * @return the scope annotation string
	 */
	String annotationForPhysicalScope(Accessibility access, PhysicalScope scope);

	/**
	 * Returns the language-specific type name for the given Java type.
	 *
	 * @param type the Java type
	 * @return the target language type name
	 */
	String nameForType(Class<?> type);

	/**
	 * Renders a {@link Method} call to a string.
	 *
	 * @param method the method call to render
	 * @return the rendered method-call expression
	 */
	String renderMethod(Method<?> method);

	/**
	 * Returns the name of the size variable associated with the given array variable.
	 *
	 * @param v the array variable
	 * @return the size variable name (default: {@code variableName + "Size"})
	 */
	default String getVariableSizeName(ArrayVariable v) {
		return v.getName() + "Size";
	}

	/**
	 * Returns {@code true} if this backend supports offset-based array access
	 * (i.e. the generated code can apply a base offset to an array pointer).
	 *
	 * @return {@code true} if variable offsets are supported
	 */
	default boolean isVariableOffsetSupported() {
		return true;
	}

	/**
	 * Returns the statement terminator string (e.g. {@code ";"} for C-family languages,
	 * or {@code ""} if statements do not require a terminator in this backend).
	 *
	 * @return the statement terminator string
	 */
	default String getStatementTerminator() {
		return "";
	}
}
