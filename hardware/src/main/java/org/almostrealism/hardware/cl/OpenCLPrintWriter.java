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

import io.almostrealism.code.Precision;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.io.PrintWriter;

/**
 * {@link org.almostrealism.io.PrintWriter} for OpenCL kernel code generation.
 *
 * <p>Generates OpenCL C code with "__kernel void" function prefix and
 * {@link OpenCLLanguageOperations} for language-specific constructs.</p>
 *
 * <h2>Generated Code Example</h2>
 *
 * <pre>{@code
 * __kernel void matmul(__global float* arg0, int arg0_offset, int arg0_size,
 *                      __global float* arg1, int arg1_offset, int arg1_size) {
 *     int idx = get_global_id(0);
 *     // kernel body...
 * }
 * }</pre>
 *
 * @see OpenCLLanguageOperations
 * @see CLComputeContext
 */
public class OpenCLPrintWriter extends CPrintWriter {

	public OpenCLPrintWriter(PrintWriter p, Precision precision) {
		super(p, null, precision, false);
		setScopePrefix("__kernel void");
		language = new OpenCLLanguageOperations(precision);
	}
}
