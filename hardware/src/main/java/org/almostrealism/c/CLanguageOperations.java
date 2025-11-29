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
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.lang.DefaultLanguageOperations;
import io.almostrealism.scope.ArrayVariable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Provides C language-specific code generation operations.
 * This class extends {@link DefaultLanguageOperations} to handle
 * C language constructs including native JNI support and argument
 * detail reads for offset/size arrays.
 */
public class CLanguageOperations extends DefaultLanguageOperations {
	/** Whether to generate native JNI-compatible code. */
	private final boolean isNative;
	/** Whether to enable reading argument offset and size arrays. */
	private final boolean enableArgumentDetailReads;

	/**
	 * Constructs a new CLanguageOperations instance.
	 *
	 * @param precision                 the floating-point precision to use (FP32 or FP64)
	 * @param isNative                  whether to generate native JNI-compatible code
	 * @param enableArgumentDetailReads whether to enable reading argument offset and size arrays
	 */
	public CLanguageOperations(Precision precision,
							   boolean isNative,
							   boolean enableArgumentDetailReads) {
		super(precision, true);
		this.isNative = isNative;
		this.enableArgumentDetailReads = enableArgumentDetailReads;
	}

	/**
	 * Returns whether native JNI code generation is enabled.
	 *
	 * @return true if native JNI code generation is enabled, false otherwise
	 */
	public boolean isNative() { return isNative; }

	/**
	 * Returns whether argument detail reads (offset/size arrays) are enabled.
	 *
	 * @return true if argument detail reads are enabled, false otherwise
	 */
	public boolean isEnableArgumentDetailReads() { return enableArgumentDetailReads; }

	/**
	 * Returns the C constant for pi based on the current precision.
	 *
	 * @return "M_PI" for FP64 precision, "M_PI_F" for FP32 precision
	 */
	@Override
	public String pi() {
		return getPrecision() == Precision.FP64 ? "M_PI" : "M_PI_F";
	}

	/**
	 * Returns the C variable name for the kernel's global index.
	 *
	 * @param index the dimension index (must be 0)
	 * @return the string "global_id"
	 * @throws IllegalArgumentException if index is not 0
	 */
	@Override
	public String kernelIndex(int index) {
		if (index != 0)
			throw new IllegalArgumentException();

		return "global_id";
	}

	/**
	 * Returns the annotation for a physical scope in C code.
	 * C does not use scope annotations, so this always returns null.
	 *
	 * @param access the accessibility level
	 * @param scope  the physical scope
	 * @return always null for C code
	 */
	@Override
	public String annotationForPhysicalScope(Accessibility access, PhysicalScope scope) {
		return null;
	}

	/**
	 * Returns the C type name for the given Java type.
	 *
	 * @param type the Java class type to convert
	 * @return the corresponding C type name
	 * @throws IllegalArgumentException if the type cannot be encoded
	 */
	@Override
	public String nameForType(Class<?> type) {
		if (type == null) return "";

		if (type == Double.class) {
			return getPrecision().typeName();
		} else if (type == Integer.class || type == int[].class) {
			return "int";
		} else if (type == Long.class || type == long[].class) {
			return "long";
		} else if (type == Boolean.class) {
			return isNumericBoolean() ? "int" : "bool";
		} else {
			throw new IllegalArgumentException("Unable to encode " + type);
		}
	}

	/**
	 * Returns whether boolean values should be represented as integers.
	 * C traditionally uses int for boolean values (0 for false, non-zero for true).
	 *
	 * @return always true for C code
	 */
	public boolean isNumericBoolean() {
		return true;
	}

	/**
	 * Renders the argument list for a C function signature.
	 * <p>
	 * For external accessibility with native mode, generates a JNI-compatible signature
	 * with pointer arrays for arguments, offsets, sizes, and a count parameter.
	 * For external accessibility with argument detail reads enabled, generates
	 * individual array parameters plus shared offsetArr and sizeArr parameters.
	 * Otherwise, delegates to the default implementation.
	 *
	 * @param arguments the list of array variables to render as arguments
	 * @param out       the consumer to receive the rendered argument string
	 * @param access    the accessibility level (EXTERNAL or INTERNAL)
	 */
	@Override
	public void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (access == Accessibility.EXTERNAL) {
			if (isNative()) {
				out.accept("long* argArr, uint32_t* offsetArr, uint32_t* sizeArr, uint32_t count");
				return;
			} else if (isEnableArgumentDetailReads() && isEnableArrayVariables()) {
				if (!arguments.isEmpty()) {
					renderArguments(arguments, out, true, true, access, ParamType.ARRAY);
					out.accept(", ");
					out.accept(annotationForPhysicalScope(access, PhysicalScope.GLOBAL));
					out.accept(" int *offsetArr");
					out.accept(argumentPost(arguments.size(), true, access));
					out.accept(", ");
					out.accept(annotationForPhysicalScope(access, PhysicalScope.GLOBAL));
					out.accept(" int *sizeArr");
					out.accept(argumentPost(arguments.size() + 1, true, access));
				}

				return;
			}
		}

		super.renderArguments(arguments, out, access);
	}

	/**
	 * Returns the statement terminator for C code.
	 *
	 * @return the semicolon character
	 */
	@Override
	public String getStatementTerminator() {
		return ";";
	}
}
