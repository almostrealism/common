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
 * Kernel traversal and series caching infrastructure for hardware-accelerated operations.
 *
 * <p>This package provides optimizations for repeated kernel execution patterns.
 * {@link org.almostrealism.hardware.kernel.KernelSeriesCache} caches expression sequences
 * to avoid recomputation, while {@link org.almostrealism.hardware.kernel.KernelTraversalOperationGenerator}
 * generates lookup-table-based traversal operations that replace costly index computations
 * with direct memory reads.</p>
 */
package org.almostrealism.hardware.kernel;
