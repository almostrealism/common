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

/**
 * Defines the visibility level of variables and operations in generated code.
 *
 * <p>Used during code generation to determine whether a variable or operation
 * should be accessible from outside the generated scope (EXTERNAL) or only
 * within the current scope (INTERNAL).</p>
 *
 * @see io.almostrealism.scope.Variable
 * @see io.almostrealism.scope.Scope
 */
public enum Accessibility {
	/** Accessible from outside the scope, typically used for input/output variables. */
	EXTERNAL,
	/** Only accessible within the current scope, used for intermediate computations. */
	INTERNAL
}
