/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.kernel.KernelStructureContext;

import java.util.Objects;
import java.util.stream.Stream;

public interface ScopeLifecycle {
	default void prepareArguments(ArgumentMap map) { }

	default void prepareScope(ScopeInputManager manager, KernelStructureContext context) { }

	default void resetArguments() { }

	static void prepareArguments(Stream<?> potentialLifecycles, ArgumentMap map) {
		potentialLifecycles
				.map(p -> p instanceof ScopeLifecycle ? (ScopeLifecycle) p : null)
				.filter(Objects::nonNull)
				.forEach(sl -> sl.prepareArguments(map));
	}

	static void prepareScope(Stream<?> potentialLifecycles, ScopeInputManager manager, KernelStructureContext context) {
		potentialLifecycles
				.map(p -> p instanceof ScopeLifecycle ? (ScopeLifecycle) p : null)
				.filter(Objects::nonNull)
				.forEach(sl -> sl.prepareScope(manager, context));
	}

	static void resetArguments(Stream<?> potentialLifecycles) {
		potentialLifecycles
				.map(p -> p instanceof ScopeLifecycle ? (ScopeLifecycle) p : null)
				.filter(Objects::nonNull)
				.forEach(sl -> sl.resetArguments());
	}
}
