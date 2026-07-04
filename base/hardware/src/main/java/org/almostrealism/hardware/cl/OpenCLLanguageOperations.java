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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.PhysicalScope;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.hardware.Hardware;
import org.jocl.cl_event;

/**
 * {@link io.almostrealism.lang.LanguageOperations} for OpenCL C code generation.
 *
 * <p>Extends {@link CLanguageOperations} with OpenCL-specific features including kernel indexing,
 * address space qualifiers (__global, __local), and volatile memory annotations.</p>
 *
 * <h2>Kernel Indexing</h2>
 *
 * <pre>{@code
 * // Generated code:
 * int idx = get_global_id(0);
 * }</pre>
 *
 * <h2>Address Space Qualifiers</h2>
 *
 * <pre>{@code
 * // GLOBAL scope generates: __global float* data
 * // LOCAL scope generates: __local float* data
 * }</pre>
 *
 * @see OpenCLPrintWriter
 * @see CLComputeContext
 */
public class OpenCLLanguageOperations extends CLanguageOperations {

	/**
	 * Creates OpenCL language operations for the given precision.
	 *
	 * @param precision Numeric precision for generated kernel code
	 */
	public OpenCLLanguageOperations(Precision precision) {
		super(precision, false, false);
	}

	/**
	 * Returns the kernel index expression with an explicit signed cast.
	 *
	 * <p>OpenCL C's {@code get_global_id} returns the unsigned {@code size_t}, so using it
	 * raw poisons any expression containing a negative intermediate: a subtraction wraps to
	 * a huge positive value and comparisons like {@code (id % n) - k >= 0} are always true.
	 * The Metal generator casts its thread position to {@code long} for the same reason;
	 * this must match, since both backends compile the same expression trees.</p>
	 *
	 * @param index Dimension index (0=x, 1=y, 2=z)
	 * @return Index expression like {@code ((long) get_global_id(0))}
	 */
	@Override
	public String kernelIndex(int index) {
		return "((long) get_global_id(" + index + "))";
	}

	/**
	 * Returns the absolute value expression using {@code fabs}.
	 *
	 * <p>{@link io.almostrealism.expression.Absolute} is always a floating point
	 * expression, and OpenCL C's {@code abs} is defined only for integer types —
	 * passing a floating point argument is ambiguous among the integer overloads
	 * and fails compilation. Metal and C++ resolve {@code abs} for floating point
	 * arguments, so only this backend needs the distinction.</p>
	 *
	 * @param value The expression to take the absolute value of
	 * @return Expression like {@code fabs(value)}
	 */
	@Override
	public String abs(String value) {
		return "fabs(" + value + ")";
	}

	@Override
	public String nameForType(Class<?> type) {
		if (type == cl_event.class) {
			return "cl_event";
		}

		return super.nameForType(type);
	}

	@Override
	public String annotationForPhysicalScope(Accessibility access, PhysicalScope scope) {
		String volatilePrefix = Hardware.getLocalHardware().isMemoryVolatile() ? "volatile " : "";
		if (scope != null) return volatilePrefix + (scope == PhysicalScope.LOCAL ? "__local" : "__global");
		return null;
	}
}
