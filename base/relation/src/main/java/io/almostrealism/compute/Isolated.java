/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.compute;

/**
 * Marker for a {@link Process} wrapper that isolates its subtree from expression
 * embedding: the wrapped computation is compiled and evaluated as its own dispatch —
 * typically during argument preparation of the process that consumes its result —
 * rather than being inlined into the consumer's kernel.
 *
 * <p>Because an isolated subtree is evaluated <em>before</em> the consumer executes,
 * any memory it reads is read ahead of the consumer's own execution order. Composition
 * machinery that fuses multiple processes into a single executable unit must therefore
 * treat an isolated subtree's reads as occurring before the fused unit runs, and keep a
 * write and an isolated read of the same memory in separately-dispatched units when the
 * write is expected to be visible to the read.</p>
 *
 * @see Process#isolate()
 */
public interface Isolated {
}
