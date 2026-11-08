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
import io.almostrealism.code.Precision;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.io.PrintWriter;

import java.util.List;

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

	/** The Metal language operations this writer renders with, which know the kernel's lanes. */
	private final MetalLanguageOperations metalLanguage;

	/**
	 * Creates a Metal print writer with default FP32 precision.
	 *
	 * @param p Underlying {@link PrintWriter} for output
	 */
	public MetalPrintWriter(PrintWriter p) {
		this(p, null, Precision.FP32);
	}

	/**
	 * Creates a Metal print writer with specified precision and kernel name.
	 *
	 * @param p Underlying {@link PrintWriter} for output
	 * @param topLevelMethodName Name of the top-level kernel function
	 * @param precision Numeric precision for code generation
	 */
	public MetalPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision) {
		this(p, topLevelMethodName, precision, 1);
	}

	/**
	 * Creates a Metal print writer for a kernel that assigns the given number of lanes to each
	 * work item (see {@link io.almostrealism.scope.Scope#getLaneCount()}).
	 *
	 * @param p Underlying {@link PrintWriter} for output
	 * @param topLevelMethodName Name of the top-level kernel function
	 * @param precision Numeric precision for code generation
	 * @param laneCount The lanes of each work item of the kernel, at least 1
	 */
	public MetalPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision, int laneCount) {
		super(p, topLevelMethodName, precision, false);
		metalLanguage = new MetalLanguageOperations(precision, laneCount);
		language = metalLanguage;
		setExternalScopePrefix("[[kernel]] void");
		setInternalScopePrefix("void");
	}

	/**
	 * Begins a scope, opening the body of a kernel that assigns several lanes to each work item
	 * with the {@link MetalLanguageOperations#laneDeclarations() declarations} of its work item
	 * and lane.
	 *
	 * @param name the scope name
	 * @param metadata operation metadata for rendering comments
	 * @param access the accessibility level of this scope
	 * @param arguments array variables passed as arguments to this scope
	 * @param parameters additional parameters for this scope
	 */
	@Override
	public void beginScope(String name, OperationMetadata metadata, Accessibility access,
						   List<ArrayVariable<?>> arguments, List<Variable<?, ?>> parameters) {
		super.beginScope(name, metadata, access, arguments, parameters);

		if (access == Accessibility.EXTERNAL) {
			metalLanguage.laneDeclarations().forEach(this::println);
		}
	}
}
