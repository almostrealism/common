/*
 * Copyright 2025 Michael Murray
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

/**
 * Core abstractions for the AR process execution and optimization framework.
 *
 * <p>This package defines the {@link io.almostrealism.compute.Process} hierarchy and the
 * parallelism/optimization infrastructure used to compile Producer expression graphs into
 * executable computation trees:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.compute.Process} — the fundamental composable unit of
 *       computational work; forms a tree with children, supports optimization before
 *       execution via {@code Process.optimize()}.</li>
 *   <li>{@link io.almostrealism.compute.ParallelProcess} — a {@code Process} that carries
 *       explicit parallelism metadata ({@link io.almostrealism.compute.Countable}).</li>
 *   <li>{@link io.almostrealism.compute.ProcessContext} — contextual information propagated
 *       through process tree traversal, including depth and optimization strategy.</li>
 *   <li>{@link io.almostrealism.compute.ProcessContextBase} — base implementation of
 *       {@code ProcessContext}, managing depth and the default
 *       {@link io.almostrealism.compute.ProcessOptimizationStrategy}.</li>
 *   <li>{@link io.almostrealism.compute.ParallelProcessContext} — extends
 *       {@code ProcessContextBase} with parallelism count, aggregation count, and
 *       fixed-parallelism flag.</li>
 *   <li>{@link io.almostrealism.compute.ProcessOptimizationStrategy} — strategy interface
 *       for rewriting process trees before execution.</li>
 *   <li>{@link io.almostrealism.compute.CascadingOptimizationStrategy} — applies a
 *       sequence of strategies in order, stopping at the first successful transformation.</li>
 * </ul>
 *
 * <p><strong>Key invariant:</strong> always call {@code Process.optimize()} before
 * {@code Process.get()}. Skipping this step breaks expression embedding and may produce
 * incorrect results.</p>
 */
package io.almostrealism.compute;
