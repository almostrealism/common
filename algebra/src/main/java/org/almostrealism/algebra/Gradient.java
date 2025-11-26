/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.relation.Producer;

/**
 * A continuously evaluable function that provides surface normal vectors at each position.
 *
 * <p>
 * {@link Gradient} extends {@link Differentiable} to represent functions that can compute
 * normal vectors (perpendicular to the tangent plane) at any point in 3D space. This is
 * fundamental for:
 * </p>
 * <ul>
 *   <li>Surface rendering and lighting calculations</li>
 *   <li>Ray tracing and intersection tests</li>
 *   <li>Physics simulations requiring surface properties</li>
 *   <li>Gradient-based optimization</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Implement a gradient for a sphere
 * public class SphereGradient implements Gradient<PackedCollection<?>> {
 *     @Override
 *     public Producer<Vector> getNormalAt(Producer<Vector> point) {
 *         // For a sphere centered at origin, normal = point / ||point||
 *         return normalize(point);
 *     }
 *
 *     @Override
 *     public Producer<PackedCollection<?>> getValueAt(Producer<Vector> point) {
 *         // Sphere implicit function: f(p) = ||p||^2 - r^2
 *         return subtract(length(point).pow(2), scalar(radius * radius));
 *     }
 * }
 * }</pre>
 *
 * @param <T>  the type of value produced by the differentiable function
 * @author  Michael Murray
 * @see Differentiable
 * @see Vector
 * @see Producer
 */
public interface Gradient<T> extends Differentiable<T> {
	/**
	 * Returns a {@link Producer} that computes the surface normal vector at the specified point.
	 *
	 * <p>
	 * The normal vector is perpendicular to the tangent plane of the surface at the given point.
	 * For implicit surfaces defined by f(x,y,z) = 0, the normal is typically the gradient gradf.
	 * </p>
	 *
	 * @param point  a producer for the 3D position where the normal should be computed
	 * @return a producer that generates the normal vector at the specified point
	 */
	Producer<Vector> getNormalAt(Producer<Vector> point);
}
