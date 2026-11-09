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

package org.almostrealism.hardware.js;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.lang.DefaultLanguageOperations;

/**
 * The language operations for generated JavaScript, which is untyped, single-threaded and
 * has no memory-space annotations.
 */
public class JavaScriptLanguageOperations extends DefaultLanguageOperations {
	/**
	 * Creates the JavaScript language operations, at single precision.
	 */
	public JavaScriptLanguageOperations() {
		super(Precision.FP32, false);
	}

	/** JavaScript runs one work item, so every kernel index is {@code 0}. */
	@Override
	public String kernelIndex(int index) { return "0"; }

	/** JavaScript variables are untyped, so no type name is written. */
	@Override
	public String nameForType(Class<?> type) { return ""; }

	/** JavaScript has no memory-space annotations. */
	@Override
	public String annotationForPhysicalScope(Accessibility access, PhysicalScope scope) { return ""; }

	@Override
	public String pi() {
		return "Math.PI";
	}

	/** JavaScript exposes exponentiation as {@code Math.pow}. */
	@Override
	public String pow(String a, String b) { return "Math.pow(" + a + ", " + b + ")"; }

	/** JavaScript exposes the minimum as {@code Math.min}. */
	@Override
	public String min(String a, String b) { return "Math.min(" + a + ", " + b + ")"; }

	/** JavaScript exposes the maximum as {@code Math.max}. */
	@Override
	public String max(String a, String b) { return "Math.max(" + a + ", " + b + ")"; }

	/** JavaScript exposes the absolute value as {@code Math.abs}. */
	@Override
	public String abs(String value) { return "Math.abs(" + value + ")"; }
}
