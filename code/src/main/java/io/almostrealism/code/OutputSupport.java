/*
 * Copyright 2021 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.scope.Variable;

/**
 * {@link OutputSupport} is an interface that provides access to the output variable
 * of a computation or operation. The output variable represents where the result
 * of a computation will be stored.
 *
 * <p>This interface is typically implemented by {@link Computation} and its subclasses
 * to provide information about the output destination for generated code.
 *
 * <h2>Usage</h2>
 * <p>The output variable is used during code generation to determine:
 * <ul>
 *   <li>Where to write computation results</li>
 *   <li>The type and shape of the output</li>
 *   <li>Memory allocation requirements</li>
 * </ul>
 *
 * <p>The default implementation returns {@code null}, indicating that there is no
 * explicit output variable. This is common for computations where the output is
 * determined dynamically or is one of the input arguments (in-place operations).
 *
 * @author Michael Murray
 * @see Computation
 * @see Variable
 */
public interface OutputSupport {

	/**
	 * Returns the output variable for this computation, if any.
	 *
	 * <p>The output variable represents the destination where the computation's
	 * result will be stored. If this method returns {@code null}, the computation
	 * either:
	 * <ul>
	 *   <li>Does not have an explicit output variable</li>
	 *   <li>Writes results to one of its input arguments (in-place operation)</li>
	 *   <li>Determines output location dynamically at runtime</li>
	 * </ul>
	 *
	 * @return the output variable, or {@code null} if there is no explicit output variable
	 */
	default Variable getOutputVariable() {
		return null;
	}
}
