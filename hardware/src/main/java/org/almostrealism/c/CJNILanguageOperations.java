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

package org.almostrealism.c;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Language operations for generating C code compatible with JNI (Java Native Interface).
 *
 * <p>Extends {@link CLanguageOperations} to produce C code that can be called from Java
 * via JNI. This includes JNI-specific type mappings (jint, jlong), function signatures
 * with JNI environment parameters, and single-precision math function variants.</p>
 *
 * @see CLanguageOperations
 */
public class CJNILanguageOperations extends CLanguageOperations {
	/** List of method names that are library functions and should not receive the global_id parameter. */
	private final List<String> libraryMethods;

	/**
	 * Creates JNI-compatible language operations with the specified precision.
	 *
	 * @param precision The floating-point precision (FP32 or FP64)
	 */
	public CJNILanguageOperations(Precision precision) {
		super(precision, true, true);
		this.libraryMethods = new ArrayList<>();
	}

	/**
	 * Returns the list of library method names.
	 *
	 * @return the library methods list
	 */
	protected List<String> getLibraryMethods() { return libraryMethods; }

	/**
	 * Returns the power expression for the given base and exponent.
	 *
	 * <p>Uses {@code powf} for single-precision (non-FP64) to match C math library conventions.</p>
	 *
	 * @param a The base expression
	 * @param b The exponent expression
	 * @return The C power expression
	 */
	@Override
	public String pow(String a, String b) {
		if (getPrecision() != Precision.FP64) {
			return "powf(" + a + ", " + b + ")";
		}

		return super.pow(a, b);
	}

	/**
	 * Returns the minimum expression using {@code fmin}.
	 *
	 * @param a The first operand
	 * @param b The second operand
	 * @return The C fmin expression
	 */
	@Override
	public String min(String a, String b) {
		return "f" + super.min(a, b);
	}

	/**
	 * Returns the maximum expression using {@code fmax}.
	 *
	 * @param a The first operand
	 * @param b The second operand
	 * @return The C fmax expression
	 */
	@Override
	public String max(String a, String b) {
		return "f" + super.max(a, b);
	}

	/**
	 * Returns the JNI type name for the given Java type.
	 *
	 * <p>Maps Java types to JNI types:</p>
	 * <ul>
	 *   <li>{@code Integer}, {@code int[]} -> {@code jint}</li>
	 *   <li>{@code Long}, {@code long[]} -> {@code jlong}</li>
	 *   <li>Other types delegate to superclass</li>
	 * </ul>
	 *
	 * @param type The Java type
	 * @return The corresponding JNI type name
	 */
	@Override
	public String nameForType(Class<?> type) {
		if (type == Integer.class || type == int[].class) {
			return "jint";
		} else if (type == Long.class || type == long[].class) {
			return "jlong";
		} else {
			return super.nameForType(type);
		}
	}

	/**
	 * Renders JNI function arguments for external access.
	 *
	 * <p>For external functions, outputs the standard JNI signature including
	 * JNIEnv, jobject, and kernel execution parameters.</p>
	 *
	 * @param arguments The array variables to render
	 * @param out The output consumer
	 * @param access The accessibility level
	 */
	@Override
	public void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (access == Accessibility.EXTERNAL) {
			out.accept("JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jint count, jint global_index, jlong global_total");
			return;
		}

		super.renderArguments(arguments, out, access);
	}

	/**
	 * Renders function parameters with an additional global_id parameter.
	 *
	 * @param arguments The variables to render as parameters
	 * @param out The output consumer
	 * @param enableType Whether to include type declarations
	 * @param enableAnnotation Whether to include annotations
	 * @param access The accessibility level
	 * @param replaceType Optional type to substitute
	 * @param prefix Prefix to prepend to each parameter
	 * @param suffix Suffix to append to each parameter
	 */
	@Override
	protected void renderParameters(List<Variable<?, ?>> arguments, Consumer<String> out, boolean enableType, boolean enableAnnotation, Accessibility access, Class replaceType, String prefix, String suffix) {
		super.renderParameters(arguments, out, enableType, enableAnnotation, access, replaceType, prefix, suffix);
		out.accept(", ");
		out.accept("jint global_id");
	}

	/**
	 * Renders method call parameters, appending global_id for non-library methods.
	 *
	 * @param methodName The name of the method being called
	 * @param parameters The parameter expressions
	 * @param out The output consumer
	 */
	@Override
	protected void renderParameters(String methodName, List<Expression> parameters, Consumer<String> out) {
		super.renderParameters(methodName, parameters, out);
		if (!getLibraryMethods().contains(methodName)) out.accept(", global_id");
	}
}
