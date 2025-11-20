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

import io.almostrealism.code.Precision;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.io.PrintWriter;

/**
 * {@link CPrintWriter} for Metal Shading Language (MSL) code generation.
 *
 * <p>Configures the print writer with Metal-specific language operations and kernel prefixes.</p>
 *
 * <h2>Kernel Prefixes</h2>
 *
 * <pre>{@code
 * // External scope (kernel entry point):
 * [[kernel]] void myKernel(...)
 *
 * // Internal scope (helper functions):
 * void myHelper(...)
 * }</pre>
 *
 * @see MetalLanguageOperations
 * @see MetalComputeContext
 */
public class MetalPrintWriter extends CPrintWriter {

	public MetalPrintWriter(PrintWriter p) {
		this(p, null, Precision.FP32);
	}

	public MetalPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision) {
		super(p, topLevelMethodName, precision, false);
		language = new MetalLanguageOperations(precision);
		setExternalScopePrefix("[[kernel]] void");
		setInternalScopePrefix("void");
	}
}
