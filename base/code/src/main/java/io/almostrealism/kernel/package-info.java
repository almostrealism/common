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

/**
 * Kernel-level optimization infrastructure for analyzing and simplifying expression trees.
 *
 * <p>This package contains the types responsible for understanding the structure of GPU kernel
 * computations and optimizing expressions against kernel index patterns. Key types:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.kernel.KernelIndex} — the GPU thread index expression variable</li>
 *   <li>{@link io.almostrealism.kernel.KernelStructureContext} — the context that defines kernel
 *       size bounds and provides series providers and traversal providers</li>
 *   <li>{@link io.almostrealism.kernel.KernelSeriesProvider} — converts expressions to arithmetic
 *       sequences for pattern-based optimization</li>
 *   <li>{@link io.almostrealism.kernel.KernelTraversalProvider} — generates reordering expressions
 *       for traversal optimization</li>
 *   <li>{@link io.almostrealism.kernel.ExpressionMatrix} — a 2D matrix of expressions used to
 *       analyze row-level duplication and simplify repeated patterns</li>
 *   <li>{@link io.almostrealism.kernel.KernelPreferences} — global configuration flags for
 *       kernel-level parallelism settings</li>
 * </ul>
 */
package io.almostrealism.kernel;
