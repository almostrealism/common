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

import java.util.Iterator;
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
 *   <li>{@link #prepareScope(ArgumentProvider, KernelStructureContext)} - Configure scope during compilation</li>
 *   <li>{@link #resetArguments()} - Clean up state after execution</li>
 * </ol>
 *
 * @see ArgumentProvider
 * @see KernelStructureContext
 */
public interface ScopeLifecycle {
	/**
	 * Prepares the scope for compilation within the given context.
	 *
	 * <p>Called during scope compilation to allow components to configure
	 * inputs and interact with the kernel structure.</p>
	 *
	 * <p>A component may be prepared more than once, with the same manager or with the
	 * manager of another compilation, so preparation must be idempotent. Since obtaining an
	 * argument from an {@link ArgumentProvider} is itself idempotent, an implementation
	 * simply requests what it needs from the manager it is given each time.</p>
	 *
	 * @param manager the scope input manager for registering inputs
	 * @param context the kernel structure context for the compilation
	 */
	default void prepareScope(ArgumentProvider manager, KernelStructureContext context) { }

	/**
	 * Returns the most kernel arguments {@link #prepareScope(ArgumentProvider, KernelStructureContext)}
	 * obtains from its manager for values that are not inputs of this component: a target a
	 * derivative is taken with respect to, the collection an ordering reads to map an index, or
	 * the arguments of another component this one prepares along with itself.
	 *
	 * <p>The arguments of a component's inputs are reached through the leaves of the process
	 * tree it belongs to, so a structural bound on a kernel's arguments counts them there. The
	 * arguments this method reports have no position in that tree, so the bound adds them
	 * explicitly; an implementation that obtains such an argument and does not report it lets a
	 * kernel judged to fit its context's argument limit exceed it. A component that prepares
	 * other components outside of the process tree reports what preparing them obtains, as
	 * {@link #getPreparedArgumentBound(Stream, int)} counts it. The count must depend only on the
	 * component's structure, never on the data it reads, so every computation sharing a compiled
	 * kernel reaches the same bound. The default is zero.</p>
	 *
	 * @param cap the count past which the exact number does not matter; must not be negative
	 * @return the most arguments preparing this component adds beyond those of its inputs if
	 *         that is at most {@code cap}, otherwise a value greater than {@code cap}
	 */
	default int getScopeArgumentCount(int cap) { return 0; }

	/**
	 * Resets argument state after scope execution.
	 *
	 * <p>Called to clean up cached argument state, allowing the component
	 * to be reused with different arguments.</p>
	 */
	default void resetArguments() { }

	/**
	 * Calls {@link #prepareScope(ArgumentProvider, KernelStructureContext)} on every element
	 * in the stream that implements {@link ScopeLifecycle}, ignoring non-lifecycle objects.
	 *
	 * @param potentialLifecycles a stream of objects that may implement ScopeLifecycle
	 * @param manager the scope input manager to pass to each lifecycle component
	 * @param context the kernel structure context to pass to each lifecycle component
	 */
	static void prepareScope(Stream<?> potentialLifecycles, ArgumentProvider manager, KernelStructureContext context) {
		potentialLifecycles
				.map(p -> p instanceof ScopeLifecycle ? (ScopeLifecycle) p : null)
				.filter(Objects::nonNull)
				.forEach(sl -> sl.prepareScope(manager, context));
	}

	/**
	 * Returns the {@link #getScopeArgumentCount(int) scope argument count} of an object that may
	 * implement {@link ScopeLifecycle}, or zero for an object that does not, since such an
	 * object is never prepared and so obtains no arguments.
	 *
	 * @param potentialLifecycle an object that may implement ScopeLifecycle
	 * @param cap                the count past which the exact number does not matter
	 * @return the most arguments preparing the object adds beyond those of its inputs if that is
	 *         at most {@code cap}, otherwise a value greater than {@code cap}
	 */
	static int getScopeArgumentCount(Object potentialLifecycle, int cap) {
		return potentialLifecycle instanceof ScopeLifecycle lifecycle ? lifecycle.getScopeArgumentCount(cap) : 0;
	}

	/**
	 * Returns an upper bound on the kernel arguments
	 * {@link #prepareScope(Stream, ArgumentProvider, KernelStructureContext) preparing} every element
	 * of the stream obtains when those elements are prepared outside of any process tree, as the
	 * dependencies a component prepares along with itself are.
	 *
	 * <p>A {@link Computation} obtains the arguments of every position of its own process tree,
	 * which its {@link Computation#getInputArgumentBound(int) input argument bound} counts; any
	 * other lifecycle obtains its {@link #getScopeArgumentCount(int) scope argument count}; an
	 * object that is not a lifecycle is never prepared and obtains nothing. The sum is counted by
	 * position, saturates at {@link Integer#MAX_VALUE}, and stops once it exceeds {@code cap}.</p>
	 *
	 * @param potentialLifecycles a stream of objects that may implement ScopeLifecycle
	 * @param cap                 the count past which the exact bound does not matter; must not
	 *                            be negative
	 * @return the bound if it is at most {@code cap}, otherwise a value greater than {@code cap}
	 */
	static int getPreparedArgumentBound(Stream<?> potentialLifecycles, int cap) {
		long sum = 0;

		Iterator<?> elements = potentialLifecycles.iterator();
		while (elements.hasNext() && sum <= cap) {
			Object element = elements.next();
			int remaining = (int) (cap - sum);
			sum += element instanceof Computation<?> computation
					? computation.getInputArgumentBound(remaining)
					: getScopeArgumentCount(element, remaining);
		}

		return (int) Math.min(Integer.MAX_VALUE, sum);
	}

	/**
	 * Calls {@link #resetArguments()} on every element in the stream that implements
	 * {@link ScopeLifecycle}, ignoring non-lifecycle objects.
	 *
	 * @param potentialLifecycles a stream of objects that may implement ScopeLifecycle
	 */
	static void resetArguments(Stream<?> potentialLifecycles) {
		potentialLifecycles
				.map(p -> p instanceof ScopeLifecycle ? (ScopeLifecycle) p : null)
				.filter(Objects::nonNull)
				.forEach(sl -> sl.resetArguments());
	}
}
