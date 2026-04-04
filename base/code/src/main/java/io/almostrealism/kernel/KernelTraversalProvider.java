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

package io.almostrealism.kernel;

import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.TimingMetric;

/**
 * Generates index reordering expressions for kernel traversal patterns.
 *
 * <p>A {@code KernelTraversalProvider} is consulted during expression simplification
 * to transform index access patterns — for example to enable cache-friendly memory
 * traversal or to reorder element access for coalescing on GPU hardware.
 * Timing data for all reordering operations is accumulated in {@link #timing}.</p>
 *
 * @see KernelStructureContext#getTraversalProvider()
 */
public interface KernelTraversalProvider {
	/** Accumulates timing data for kernel traversal reordering operations. */
	TimingMetric timing = Scope.console.timing("kernelTraversal");

	/**
	 * Returns an expression that reorders the index accesses within the given expression
	 * to match the desired traversal pattern.
	 *
	 * @param expression the expression whose index access order should be rewritten
	 * @return a new expression with reordered index accesses
	 */
	Expression<?> generateReordering(Expression<?> expression);

	/**
	 * Clears all accumulated timing data from the {@link #timing} metric.
	 */
	static void clearTimes() {
		KernelTraversalProvider.timing.clear();
	}

	/**
	 * Prints accumulated traversal timing data if the total exceeds 10 seconds.
	 */
	static void printTimes() {
		printTimes(false);
	}

	/**
	 * Prints accumulated traversal timing data, optionally including all entries
	 * regardless of the total duration.
	 *
	 * @param verbose {@code true} to always print; {@code false} to print only when total &gt; 10 seconds
	 */
	static void printTimes(boolean verbose) {
		if (verbose || KernelTraversalProvider.timing.getTotal() > 10) {
			KernelTraversalProvider.timing.print();
		}
	}
}
