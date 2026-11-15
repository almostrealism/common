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

import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Computable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

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
	 * Indicates whether the kernel compiled from this computation may refer to kernel structure
	 * resources: the precomputed series of a {@link io.almostrealism.kernel.KernelSeriesProvider}
	 * and the traversal operations a {@link io.almostrealism.kernel.KernelTraversalProvider}
	 * generates (see {@link KernelStructureContext}). The default is {@code true}; a computation
	 * that is itself one of those resources, generated for another kernel, returns {@code false},
	 * so that compiling it never creates resources of its own.
	 *
	 * @return {@code true} if the kernel may refer to kernel structure resources
	 */
	default boolean isKernelStructureSupported() { return true; }

	/**
	 * Returns an upper bound on the kernel arguments preparing this computation's scope obtains,
	 * excluding its output: the leaf positions of its {@link Process} tree plus the
	 * {@link ScopeLifecycle#getScopeArgumentCount() scope argument count} of every position of
	 * that tree. Both are counted by position, before any de-duplication by value or by shared
	 * memory, so the real count can only be lower. The tree is what the computation's signature
	 * describes, so the bound is the same for every computation sharing a compiled kernel.
	 *
	 * <p>A computation that is not a {@link Process} has no structure to count and is reported
	 * as unbounded ({@link Integer#MAX_VALUE}).</p>
	 *
	 * @param cap the count past which the exact bound does not matter
	 * @return the bound if it is at most {@code cap}, otherwise a value greater than {@code cap}
	 */
	default int getInputArgumentBound(int cap) {
		if (!(this instanceof Process<?, ?> process)) {
			return Integer.MAX_VALUE;
		}

		int leaves = process.countLeaves(cap);
		if (leaves > cap) return leaves;

		return (int) Math.min(Integer.MAX_VALUE,
				(long) leaves + process.sumByPosition(ScopeLifecycle::getScopeArgumentCount, cap - leaves));
	}

	/**
	 * Returns the console instance for logging.
	 *
	 * @return the shared computation console
	 */
	@Override
	default Console console() { return console; }
}
