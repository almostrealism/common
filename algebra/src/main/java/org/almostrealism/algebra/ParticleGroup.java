/*
 * Copyright 2017 Michael Murray
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

/**
 * Interface for objects that contain a collection of particle positions (vertices).
 *
 * <p>
 * {@link ParticleGroup} provides access to particle position data represented as a 2D array
 * where each row represents a single particle's 3D coordinates {x, y, z}. This interface is
 * typically used in:
 * <ul>
 *   <li>Particle systems and simulations</li>
 *   <li>Point cloud representations</li>
 *   <li>Geometry and mesh processing</li>
 *   <li>Physics simulations</li>
 * </ul>
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class SimpleParticleSystem implements ParticleGroup {
 *     private double[][] particles;
 *
 *     public SimpleParticleSystem(int count) {
 *         particles = new double[count][3];
 *         // Initialize particle positions...
 *     }
 *
 *     public double[][] getParticleVertices() {
 *         return particles;
 *     }
 * }
 *
 * // Usage
 * ParticleGroup group = new SimpleParticleSystem(100);
 * double[][] vertices = group.getParticleVertices();
 * for (double[] particle : vertices) {
 *     double x = particle[0];
 *     double y = particle[1];
 *     double z = particle[2];
 *     // Process particle position...
 * }
 * }</pre>
 *
 * @author  Michael Murray
 * @see Vector
 */
public interface ParticleGroup {
	/**
	 * Returns the particle positions as a 2D array.
	 *
	 * <p>
	 * The returned array has shape [N][3] where N is the number of particles.
	 * Each row contains the {x, y, z} coordinates of a single particle.
	 * </p>
	 *
	 * @return a 2D array of particle vertices, where each row is {x, y, z}
	 */
	double[][] getParticleVertices();
}
