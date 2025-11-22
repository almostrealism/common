/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.optimize;

/**
 * Thread-safe aggregator for collecting and computing fitness score statistics.
 * <p>
 * {@code HealthScoring} collects {@link HealthScore} values from multiple fitness
 * evaluations and provides aggregate statistics including average and maximum scores.
 * This class is designed for concurrent access, making it suitable for parallel
 * fitness evaluation in {@link PopulationOptimizer}.
 * </p>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * All methods that modify or read accumulated statistics are synchronized,
 * ensuring correct behavior when fitness evaluations run in parallel threads.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * HealthScoring scoring = new HealthScoring(100);  // Population size of 100
 *
 * // Push scores from parallel fitness evaluations
 * scoring.pushScore(() -> 0.75);
 * scoring.pushScore(() -> 0.82);
 * scoring.pushScore(() -> 0.91);
 *
 * // Get statistics
 * double avg = scoring.getAverageScore();  // Average of all pushed scores
 * double max = scoring.getMaxScore();      // Highest score seen (0.91)
 * }</pre>
 *
 * @see HealthScore
 * @see HealthCallable
 * @see PopulationOptimizer
 *
 * @author Michael Murray
 */
public class HealthScoring {
	private int popSize;
	private double highestHealth, totalHealth;

	/**
	 * Creates a new scoring aggregator for the specified population size.
	 *
	 * @param popSize the expected number of fitness evaluations; used to compute averages
	 */
	public HealthScoring(int popSize) {
		this.popSize = popSize;
	}

	/**
	 * Records a fitness score from an organism evaluation.
	 * <p>
	 * This method updates the running total for average calculation and tracks
	 * the maximum score seen. Thread-safe for concurrent access.
	 * </p>
	 *
	 * @param health the fitness score to record; must not be null
	 */
	public synchronized void pushScore(HealthScore health) {
		if (health.getScore() > highestHealth) highestHealth = health.getScore();
		totalHealth += health.getScore();
	}

	/**
	 * Returns the average fitness score across all recorded evaluations.
	 * <p>
	 * The average is computed as the sum of all pushed scores divided by the
	 * population size specified at construction. Thread-safe for concurrent access.
	 * </p>
	 *
	 * @return the average fitness score
	 */
	public synchronized double getAverageScore() {
		return totalHealth / popSize;
	}

	/**
	 * Returns the highest fitness score recorded.
	 * <p>
	 * This represents the best-performing organism in the current generation.
	 * Thread-safe for concurrent access.
	 * </p>
	 *
	 * @return the maximum fitness score seen
	 */
	public synchronized double getMaxScore() {
		return highestHealth;
	}
}
