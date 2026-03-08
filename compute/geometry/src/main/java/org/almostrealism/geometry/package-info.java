/*
 * Copyright 2016 Michael Murray
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
 * Provides core 3D geometric primitives, transformation systems, and ray tracing infrastructure.
 *
 * <h2>Key Components</h2>
 *
 * <h3>Geometric Primitives</h3>
 * <ul>
 *   <li>{@link org.almostrealism.geometry.Ray} - A 3D ray with origin and direction</li>
 *   <li>{@link org.almostrealism.geometry.TransformMatrix} - 4x4 homogeneous transformation matrix</li>
 *   <li>{@link org.almostrealism.geometry.BasicGeometry} - Base class for positioned, oriented, and scaled objects</li>
 *   <li>{@link org.almostrealism.geometry.BoundingSolid} - Axis-aligned bounding box (AABB)</li>
 * </ul>
 *
 * <h3>Ray-Surface Intersection</h3>
 * <ul>
 *   <li>{@link org.almostrealism.geometry.Intersectable} - Interface for ray-testable surfaces</li>
 *   <li>{@link org.almostrealism.geometry.Intersection} - Stores ray-surface intersection data</li>
 *   <li>{@link org.almostrealism.geometry.ShadableIntersection} - Extended intersection with shading data</li>
 *   <li>{@link org.almostrealism.geometry.ClosestIntersection} - Finds nearest intersection among surfaces</li>
 * </ul>
 *
 * <h3>Spatial Fields</h3>
 * <ul>
 *   <li>{@link org.almostrealism.geometry.DiscreteField} - Collection of points with directions</li>
 *   <li>{@link org.almostrealism.geometry.ContinuousField} - Continuously evaluable field with gradients</li>
 *   <li>{@link org.almostrealism.geometry.Curve} - Maps 3D points to arbitrary values</li>
 * </ul>
 *
 * <h3>Feature Interfaces</h3>
 * <ul>
 *   <li>{@link org.almostrealism.geometry.RayFeatures} - Factory methods for ray operations</li>
 *   <li>{@link org.almostrealism.geometry.GeometryFeatures} - Comprehensive geometric utilities</li>
 *   <li>{@link org.almostrealism.geometry.TransformMatrixFeatures} - Matrix creation and transformation</li>
 * </ul>
 *
 * <h3>Supporting Interfaces</h3>
 * <ul>
 *   <li>{@link org.almostrealism.geometry.Positioned} - Objects with 3D position</li>
 *   <li>{@link org.almostrealism.geometry.Oriented} - Objects with 3D orientation</li>
 *   <li>{@link org.almostrealism.geometry.Scaled} - Objects with scale factors</li>
 *   <li>{@link org.almostrealism.geometry.Camera} - Interface for camera ray generation</li>
 * </ul>
 *
 * <h2>Related Packages</h2>
 * <ul>
 *   <li>{@link org.almostrealism.projection} - Camera implementations (pinhole, orthographic, thin lens)</li>
 *   <li>{@link org.almostrealism.geometry.computations} - Hardware-accelerated geometric computations</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see org.almostrealism.algebra
 * @see org.almostrealism.projection
 */
package org.almostrealism.geometry;