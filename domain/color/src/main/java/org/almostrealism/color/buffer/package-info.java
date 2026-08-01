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
 * Color and vector accumulation buffers for UV-mapped surfaces in physically-based rendering.
 *
 * <p>This package provides interfaces and implementations for storing and retrieving
 * {@link org.almostrealism.color.RGB} color samples and 3D vectors at 2D UV texture
 * coordinates. Buffers support separate front and back surface accumulators to handle
 * two-sided materials.</p>
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link org.almostrealism.color.buffer.ColorBuffer} — interface for UV-mapped
 *       color accumulation buffers</li>
 *   <li>{@link org.almostrealism.color.buffer.ArrayColorBuffer} — array-backed buffer
 *       with bilinear interpolation</li>
 *   <li>{@link org.almostrealism.color.buffer.TriangularMeshColorBuffer} — triangular
 *       mesh tree buffer with barycentric interpolation</li>
 *   <li>{@link org.almostrealism.color.buffer.SpanningTreeColorBuffer} — placeholder
 *       for a future spanning-tree implementation</li>
 *   <li>{@link org.almostrealism.color.buffer.AveragedVectorMap2D} — interface for
 *       averaged 3D vector maps at UV coordinates</li>
 *   <li>{@link org.almostrealism.color.buffer.AveragedVectorMap2D96Bit} — 96-bit
 *       fixed-point implementation of {@link org.almostrealism.color.buffer.AveragedVectorMap2D}</li>
 * </ul>
 */
package org.almostrealism.color.buffer;
