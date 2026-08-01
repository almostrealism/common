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
 * Triangle mesh operations for 3D geometry processing.
 *
 * <p>This package provides tools for creating and processing triangle meshes,
 * including ray-triangle intersection tests based on the Moller-Trumbore algorithm.</p>
 *
 * <p>Triangles are represented as packed collections of four 3D vectors: two edge
 * vectors, a position vector, and a unit normal vector. This compact representation
 * is optimized for GPU-accelerated ray-triangle intersection.</p>
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link org.almostrealism.graph.mesh.TriangleFeatures} - Factory interface
 *       for creating and decomposing triangle data structures</li>
 *   <li>{@link org.almostrealism.graph.mesh.TriangleIntersectAt} - Moller-Trumbore
 *       ray-triangle intersection computation</li>
 * </ul>
 *
 * @see org.almostrealism.graph.mesh.TriangleFeatures
 * @see org.almostrealism.graph.mesh.TriangleIntersectAt
 */
package org.almostrealism.graph.mesh;
