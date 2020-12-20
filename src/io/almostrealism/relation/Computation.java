/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.relation;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;

public interface Computation<T> {
	default void prepareScope(ScopeInputManager manager) {
	}

	/**
	 * Return a {@link Scope} containing the {@link Variable}s
	 * and {@link io.almostrealism.code.Method}s necessary to compute the output of
	 * this {@link Computation}. {@link Variable}s and {@link io.almostrealism.code.Method}s
	 * introduced should be named using the specified {@link NameProvider}.
	 */
	Scope<T> getScope(NameProvider provider);
}
