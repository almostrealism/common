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

package io.almostrealism.kernel;

/**
 * An interface for structures that can be simplified within a kernel context.
 *
 * <p>Kernel structures represent components of computational kernels that may
 * contain redundant or complex expressions that can be simplified for more
 * efficient execution. This interface provides a standardized way to apply
 * simplification transformations.</p>
 *
 * <p>Simplification is performed recursively with depth tracking, allowing
 * implementations to make decisions based on how deep they are in the
 * structure hierarchy. This is useful for controlling optimization levels
 * and preventing infinite recursion.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * KernelStructure<?> structure = ...;
 * KernelStructureContext ctx = new KernelStructureContext();
 *
 * // Simplify from the root (depth 0)
 * KernelStructure<?> simplified = structure.simplify(ctx);
 *
 * // Or specify a depth explicitly
 * KernelStructure<?> simplified = structure.simplify(ctx, 2);
 * }</pre>
 *
 * @param <T> the self-referential type, ensuring simplify returns the same type
 *
 * @see KernelStructureContext
 *
 * @author Michael Murray
 */
public interface KernelStructure<T extends KernelStructure> {

	/**
	 * Simplifies this structure starting at depth zero.
	 *
	 * <p>This is a convenience method that delegates to
	 * {@link #simplify(KernelStructureContext, int)} with depth 0.</p>
	 *
	 * @param context the context providing simplification rules and state
	 * @return a simplified version of this structure, or the same instance
	 *         if no simplification was possible
	 */
	default T simplify(KernelStructureContext context) {
		return simplify(context, 0);
	}

	/**
	 * Simplifies this structure at the specified depth.
	 *
	 * <p>Implementations should apply any applicable simplification rules
	 * and recursively simplify child structures, incrementing the depth
	 * for each level of recursion.</p>
	 *
	 * @param context the context providing simplification rules and state
	 * @param depth   the current depth in the structure hierarchy, starting at 0
	 *                for the root structure
	 * @return a simplified version of this structure, or the same instance
	 *         if no simplification was possible
	 */
	T simplify(KernelStructureContext context, int depth);
}
