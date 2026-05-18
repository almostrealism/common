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
 * Symbolic index variables, index value containers, and kernel series abstractions.
 *
 * <p>This package provides the building blocks for describing how kernel computations
 * iterate over multi-dimensional spaces and how index patterns are analyzed and
 * represented as compact arithmetic expressions.</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.sequence.Index} — a named, bounded dimension variable</li>
 *   <li>{@link io.almostrealism.sequence.DefaultIndex} — a concrete index with an optional limit</li>
 *   <li>{@link io.almostrealism.sequence.IndexChild} — a composite index encoding
 *       {@code parent * childLimit + child} as a flat integer</li>
 *   <li>{@link io.almostrealism.sequence.IndexValues} — a mapping from index names to concrete values
 *       used during expression evaluation</li>
 *   <li>{@link io.almostrealism.sequence.IndexSequence} — a sequence of numeric index values
 *       that can be analyzed for patterns and converted to expressions</li>
 *   <li>{@link io.almostrealism.sequence.KernelSeries} — represents the periodicity and scale
 *       of a kernel-index expression</li>
 *   <li>{@link io.almostrealism.sequence.KernelSeriesMatcher} — factory for default kernel
 *       series providers</li>
 *   <li>{@link io.almostrealism.sequence.Sequence} — generic ordered sequence interface</li>
 *   <li>{@link io.almostrealism.sequence.SequenceGenerator} — produces index sequences from
 *       concrete index values</li>
 * </ul>
 */
package io.almostrealism.sequence;
