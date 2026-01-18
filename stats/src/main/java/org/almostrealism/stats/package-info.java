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
 * Provides probability distribution utilities and statistical sampling for graphics and physics simulations.
 *
 * <p>This package contains utilities for working with probability distributions, particularly
 * those used in physically-based rendering and Monte Carlo simulations.</p>
 *
 * <h2>Core Components</h2>
 *
 * <h3>Distribution Sampling</h3>
 * <ul>
 *   <li>{@link org.almostrealism.stats.DistributionFeatures} - Utilities for sampling from discrete distributions and softmax</li>
 *   <li>{@link org.almostrealism.stats.SphericalProbabilityDistribution} - Interface for spherical distributions (e.g., BRDF)</li>
 * </ul>
 *
 * <h3>Graphics/Physics</h3>
 * <ul>
 *   <li>{@link org.almostrealism.stats.BRDF} - Bidirectional Reflectance Distribution Function interface</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Discrete Distribution Sampling</h3>
 * <pre>{@code
 * DistributionFeatures features = new DistributionFeatures() {};
 *
 * // Create a probability distribution
 * PackedCollection distribution = new PackedCollection(3);
 * distribution.set(0, 0.2);  // 20% probability for index 0
 * distribution.set(1, 0.5);  // 50% probability for index 1
 * distribution.set(2, 0.3);  // 30% probability for index 2
 *
 * // Sample from the distribution
 * int sample = features.sample(distribution);
 * }</pre>
 *
 * <h3>Softmax</h3>
 * <pre>{@code
 * CollectionProducer logits = ...;
 * CollectionProducer probabilities = features.softmax(logits);
 * }</pre>
 *
 * @author Michael Murray
 */
package org.almostrealism.stats;
