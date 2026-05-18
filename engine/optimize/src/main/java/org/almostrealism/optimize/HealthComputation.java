/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.time.Temporal;

/**
 * Defines the fitness evaluation strategy for organisms in an evolutionary algorithm.
 * <p>
 * {@code HealthComputation} is responsible for evaluating the fitness of a target
 * organism (typically a neural network or other temporal entity) and producing a
 * {@link HealthScore}. This interface is central to the genetic algorithm framework,
 * as it determines how organisms are ranked for selection and breeding.
 * </p>
 *
 * <h2>Lifecycle Management</h2>
 * <p>
 * This interface extends {@link Lifecycle}, providing hooks for resource management:
 * </p>
 * <ul>
 *   <li>{@code init()} - Initialize resources before evaluation begins</li>
 *   <li>{@code reset()} - Clean up after each evaluation cycle</li>
 *   <li>{@code destroy()} - Release all resources when no longer needed</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class AccuracyHealthComputation implements HealthComputation<NeuralNetwork, HealthScore> {
 *     private NeuralNetwork target;
 *     private Dataset testData;
 *
 *     public AccuracyHealthComputation(Dataset testData) {
 *         this.testData = testData;
 *     }
 *
 *     @Override
 *     public void setTarget(NeuralNetwork target) {
 *         this.target = target;
 *     }
 *
 *     @Override
 *     public HealthScore computeHealth() {
 *         double accuracy = evaluateAccuracy(target, testData);
 *         return () -> accuracy;
 *     }
 *
 *     @Override
 *     public void reset() {
 *         // Clean up between evaluations
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of temporal entity being evaluated (e.g., neural network)
 * @param <S> the type of health score produced by the computation
 *
 * @see HealthScore
 * @see HealthCallable
 * @see PopulationOptimizer
 * @see AverageHealthComputationSet
 *
 * @author Michael Murray
 */
public interface HealthComputation<T extends Temporal, S extends HealthScore> extends Lifecycle {
	/**
	 * Sets the target organism to be evaluated.
	 * <p>
	 * This method must be called before {@link #computeHealth()} to specify
	 * which organism should be evaluated for fitness.
	 * </p>
	 *
	 * @param target the temporal entity to evaluate; must not be null
	 */
	void setTarget(T target);

	/**
	 * Computes and returns the fitness score for the current target.
	 * <p>
	 * This method performs the actual fitness evaluation, which may involve
	 * running the target through test cases, measuring performance metrics,
	 * or other domain-specific evaluation criteria.
	 * </p>
	 *
	 * @return the computed health score for the target organism
	 * @throws IllegalStateException if {@link #setTarget(Temporal)} has not been called
	 */
	S computeHealth();
}
