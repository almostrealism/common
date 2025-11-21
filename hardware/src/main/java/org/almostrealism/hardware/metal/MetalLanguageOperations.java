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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Accessibility;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.CLanguageOperations;

import java.util.List;
import java.util.function.Consumer;

/**
 * {@link io.almostrealism.lang.LanguageOperations} for Metal Shading Language (MSL) code generation.
 *
 * <p>Extends {@link CLanguageOperations} with Metal-specific syntax including address space
 * qualifiers (device/thread), kernel attributes, and buffer bindings.</p>
 *
 * <h2>Address Space Qualifiers</h2>
 *
 * <pre>{@code
 * // GLOBAL scope generates: device float* data [[buffer(0)]]
 * // LOCAL scope generates: thread float* data
 * }</pre>
 *
 * <h2>Kernel Parameters</h2>
 *
 * <pre>{@code
 * // Generated kernel signature:
 * kernel void matmul(
 *     device float* arg0 [[buffer(0)]],
 *     uint global_id [[thread_position_in_grid]],
 *     uint global_count [[threads_per_grid]]
 * )
 * }</pre>
 *
 * @see MetalPrintWriter
 * @see MetalComputeContext
 */
public class MetalLanguageOperations extends CLanguageOperations {
	/**
	 * Enables use of "thread" address space qualifier instead of "device" for internal variables.
	 */
	public static boolean enableThreadScope = false;

	/**
	 * Creates Metal language operations with specified precision.
	 *
	 * @param precision Numeric precision ({@link Precision#FP16} or {@link Precision#FP32})
	 */
	public MetalLanguageOperations(Precision precision) {
		super(precision, false, true);
	}

	/**
	 * Returns kernel index expression with explicit long cast for Metal.
	 *
	 * <p>Metal requires explicit casts to avoid ambiguity in index calculations.</p>
	 *
	 * @param index Dimension index (0=x, 1=y, 2=z)
	 * @return Index expression like {@code ((long)global_id)}
	 */
	@Override
	public String kernelIndex(int index) {
		return "((long)" + super.kernelIndex(index) + ")";
	}

	/**
	 * Returns Metal address space qualifier for a physical scope.
	 *
	 * <p>Returns "device" for global/external memory, "thread" for local memory
	 * (if {@link #enableThreadScope} is enabled).</p>
	 *
	 * @param access Accessibility level (EXTERNAL for kernel parameters)
	 * @param scope Physical memory scope (GLOBAL, LOCAL, etc.)
	 * @return "device" or "thread" qualifier, or null if no qualifier needed
	 */
	@Override
	public String annotationForPhysicalScope(Accessibility access, PhysicalScope scope) {
		if (scope != null)
			return (!enableThreadScope || access == Accessibility.EXTERNAL) ? "device" : "thread";

		return null;
	}

	/**
	 * Returns address space annotation for local array declarations.
	 *
	 * <p>Returns "thread" for double arrays when {@link #enableThreadScope} is enabled.</p>
	 *
	 * @param type Array element type
	 * @param length Array length expression
	 * @return Address space qualifier or null
	 */
	@Override
	public String annotationForLocalArray(Class type, String length) {
		if (enableThreadScope && type == Double.class) return "thread";
		return super.annotationForLocalArray(type, length);
	}

	/**
	 * Checks if this language supports 64-bit integers.
	 *
	 * @return Always true (Metal supports int64_t/long)
	 */
	@Override
	public boolean isInt64() { return true; }

	/**
	 * Checks if booleans are represented as numeric types.
	 *
	 * @return Always false (Metal has native bool type)
	 */
	@Override
	public boolean isNumericBoolean() {
		return false;
	}

	/**
	 * Renders method call parameters with added global_id and global_count arguments.
	 *
	 * <p>Appends {@code , global_id, global_count} to all method calls to pass
	 * kernel execution context.</p>
	 *
	 * @param methodName Name of method being called
	 * @param parameters Parameter expressions
	 * @param out Consumer to receive rendered parameter string
	 */
	@Override
	protected void renderParameters(String methodName, List<Expression> parameters, Consumer<String> out) {
		super.renderParameters(methodName, parameters, out);
		out.accept(", global_id, global_count");
	}

	/**
	 * Renders kernel parameter declarations with Metal-specific attributes.
	 *
	 * <p>For external (kernel) functions, adds Metal attributes like
	 * {@code [[thread_position_in_grid]]} and {@code [[threads_per_grid]]}.</p>
	 *
	 * @param arguments Parameter variables to render
	 * @param out Consumer to receive rendered parameter declarations
	 * @param access Accessibility level (EXTERNAL for kernel functions)
	 */
	@Override
	public void renderParameters(List<Variable<?, ?>> arguments, Consumer<String> out, Accessibility access) {
		super.renderParameters(arguments, out, access);

		if (access == Accessibility.EXTERNAL) {
			out.accept(", ");
			out.accept("uint global_id [[thread_position_in_grid]], ");
			out.accept("uint global_count [[threads_per_grid]]");
		} else {
			out.accept(", ");
			out.accept("uint global_id, ");
			out.accept("uint global_count");
		}
	}

	/**
	 * Returns Metal buffer binding annotation for kernel arguments.
	 *
	 * <p>For external kernel parameters, returns {@code [[buffer(N)]]} attribute
	 * to bind buffers at specific indices.</p>
	 *
	 * @param index Buffer argument index
	 * @param enableAnnotation Whether annotations are enabled
	 * @param access Accessibility level
	 * @return Buffer binding annotation like {@code [[buffer(0)]]} or empty string
	 */
	@Override
	protected String argumentPost(int index, boolean enableAnnotation, Accessibility access) {
		return (enableAnnotation && access == Accessibility.EXTERNAL) ? " [[buffer(" + index + ")]]" : super.argumentPost(index, enableAnnotation, access);
	}
}
