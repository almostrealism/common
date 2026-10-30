/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.Supplier;

/**
 * A {@link Computation} is a {@link Computable} that describes a computational
 * process specifically via a {@link Scope}. This interface is the primary abstraction
 * for defining executable computations in the Almost Realism framework.
 *
 * <p>{@link Computation} extends several key interfaces:
 * <ul>
 *   <li>{@link Computable} - Provides the foundation for computable operations</li>
 *   <li>{@link ScopeLifecycle} - Manages the compilation lifecycle of scopes</li>
 *   <li>{@link OutputSupport} - Provides access to output variables</li>
 *   <li>{@link ConsoleFeatures} - Provides logging capabilities</li>
 * </ul>
 *
 * <p>The central method {@link #getScope(KernelStructureContext)} returns a {@link Scope}
 * that contains all the variables, methods, and expressions needed to execute this
 * computation. The scope is then compiled into executable code by a {@link ComputeContext}.
 *
 * <h2>Implementation</h2>
 * <p>Most implementations should extend {@link ComputationBase} rather than implementing
 * this interface directly. {@link ComputationBase} provides default implementations
 * for scope lifecycle management and argument handling.
 *
 * <h2>Console Logging</h2>
 * <p>This interface provides a shared {@link Console} instance for logging computation-related
 * messages. The console is a child of the {@link Scope#console}, allowing for hierarchical
 * log filtering.
 *
 * @param <T> the type of the ultimate result of computation
 *
 * @author Michael Murray
 * @see ComputationBase
 * @see Scope
 * @see Computable
 */
public interface Computation<T> extends
		Computable, ScopeLifecycle, OutputSupport, ConsoleFeatures {

	/**
	 * Shared console instance for logging computation-related messages.
	 * This console is a child of {@link Scope#console}.
	 */
	Console console = Scope.console.child();

	/**
	 * Returns a {@link Scope} containing the {@link Variable}s and {@link Method}s
	 * necessary to compute the output of this {@link Computation}.
	 *
	 * <p>The returned scope represents the complete computation logic that will be
	 * compiled into executable code. It includes:
	 * <ul>
	 *   <li>Input variable declarations</li>
	 *   <li>Intermediate calculation variables</li>
	 *   <li>Output variable assignments</li>
	 *   <li>Method calls and expressions</li>
	 * </ul>
	 *
	 * @param context the kernel structure context providing compilation settings
	 *                and shared resources for scope generation
	 * @return the scope containing this computation's logic
	 */
	Scope<T> getScope(KernelStructureContext context);

	/**
	 * Returns the producer of the memory this computation writes, when that memory is the only
	 * memory its {@link #getScope(KernelStructureContext) scope} writes, or {@code null} when the
	 * computation does not declare it.
	 *
	 * <p>Hardware that stages a computation's arguments in temporary memory (folding small
	 * arguments into one aggregate kernel argument, for example) has to carry the staged values
	 * of everything the kernel wrote back to their sources afterward. A declared destination
	 * limits that copy-back to the destination; without one every staged argument that can be
	 * written is copied back. A computation that declares a destination therefore must not
	 * write any other argument.</p>
	 *
	 * @return the producer of the only memory this computation writes, or {@code null}
	 */
	default Supplier<? extends Evaluable<?>> getWrittenDestination() { return null; }

	/**
	 * Returns the console instance for logging.
	 *
	 * @return the shared computation console
	 */
	@Override
	default Console console() { return console; }
}
