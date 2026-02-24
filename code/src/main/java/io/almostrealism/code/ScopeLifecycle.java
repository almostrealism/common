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

/**
 * Lifecycle interface for components that participate in scope compilation and execution.
 *
 * <p>{@link ScopeLifecycle} provides hooks for preparing arguments before compilation,
 * preparing the scope during compilation, and resetting state after execution. Components
 * that need to participate in the scope lifecycle (such as {@link io.almostrealism.relation.Producer}
 * implementations) should implement this interface.</p>
 *
 * <h2>Lifecycle Phases</h2>
 * <ol>
 *   <li>{@link #prepareArguments(ArgumentMap)} - Register required arguments before compilation</li>
 *   <li>{@link #prepareScope(ScopeInputManager, KernelStructureContext)} - Configure scope during compilation</li>
 *   <li>{@link #resetArguments()} - Clean up state after execution</li>
 * </ol>
 *
 * @see ArgumentMap
 * @see ScopeInputManager
 * @see KernelStructureContext
 */
public interface ScopeLifecycle {
	/**
	 * Prepares arguments by registering them with the argument map.
	 *
	 * <p>Called before scope compilation to collect all required arguments
	 * from participating components.</p>
	 *
	 * @param map the argument map to register arguments with
	 */
	default void prepareArguments(ArgumentMap map) { }

	/**
	 * Prepares the scope for compilation within the given context.
	 *
	 * <p>Called during scope compilation to allow components to configure
	 * inputs and interact with the kernel structure.</p>
	 *
	 * @param manager the scope input manager for registering inputs
	 * @param context the kernel structure context for the compilation
	 */
	default void prepareScope(ScopeInputManager manager, KernelStructureContext context) { }

	/**
	 * Resets argument state after scope execution.
	 *
	 * <p>Called to clean up cached argument state, allowing the component
	 * to be reused with different arguments.</p>
	 */
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
