/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.optimize;

/**
 * Represents a fitness score for an organism in an evolutionary algorithm.
 * <p>
 * {@code HealthScore} is the fundamental interface for expressing fitness values
 * in population-based optimization. Implementations provide a single numeric score
 * that indicates how well an organism performs relative to the optimization objective.
 * </p>
 *
 * <h2>Score Interpretation</h2>
 * <ul>
 *   <li>Higher scores typically indicate better fitness</li>
 *   <li>Scores are commonly normalized to the range [0.0, 1.0]</li>
 *   <li>The {@link PopulationOptimizer} uses scores to rank organisms for breeding</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Anonymous implementation for simple cases
 * HealthScore score = () -> 0.85;
 *
 * // Custom implementation with additional metadata
 * public class DetailedScore implements HealthScore {
 *     private double fitness;
 *     private double accuracy;
 *     private double speed;
 *
 *     public double getScore() { return fitness; }
 *     public double getAccuracy() { return accuracy; }
 *     public double getSpeed() { return speed; }
 * }
 * }</pre>
 *
 * @see HealthComputation
 * @see HealthScoring
 * @see PopulationOptimizer
 *
 * @author Michael Murray
 */
public interface HealthScore {
	/**
	 * Returns the fitness score value.
	 * <p>
	 * This value represents the overall fitness of an organism. Higher values
	 * indicate better fitness. The score is typically normalized to [0.0, 1.0],
	 * but implementations may use different ranges based on the optimization problem.
	 * </p>
	 *
	 * @return the fitness score, where higher values indicate better fitness
	 */
	double getScore();
}
