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

import org.almostrealism.time.Temporal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A composite {@link HealthComputation} that averages scores from multiple health computations.
 * <p>
 * {@code AverageHealthComputationSet} enables multi-objective optimization by combining multiple
 * fitness metrics into a single aggregate score. When {@link #computeHealth()} is called, it
 * evaluates all contained health computations and returns their average score.
 * </p>
 *
 * <h2>Multi-Objective Optimization</h2>
 * <p>
 * This class is useful when evaluating organisms on multiple criteria simultaneously:
 * </p>
 * <ul>
 *   <li>Accuracy vs. efficiency tradeoffs</li>
 *   <li>Speed vs. quality metrics</li>
 *   <li>Multiple test scenarios</li>
 *   <li>Robustness across different inputs</li>
 * </ul>
 *
 * <h2>Listener Support</h2>
 * <p>
 * Listeners can be registered to observe individual health computation evaluations.
 * This is useful for logging or debugging individual metrics.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * AverageHealthComputationSet<MyOrganism> composite = new AverageHealthComputationSet<>();
 *
 * // Add multiple fitness metrics
 * composite.add(new AccuracyHealthComputation());
 * composite.add(new SpeedHealthComputation());
 * composite.add(new EfficiencyHealthComputation());
 *
 * // Optional: observe individual evaluations
 * composite.addListener((computation, target) ->
 *     log("Evaluating " + computation.getClass().getSimpleName())
 * );
 *
 * // Evaluate composite score
 * composite.setTarget(organism);
 * HealthScore avgScore = composite.computeHealth();  // Average of all metrics
 * }</pre>
 *
 * @param <T> the type of temporal entity being evaluated
 *
 * @see HealthComputation
 * @see HealthScore
 * @see PopulationOptimizer
 *
 * @author Michael Murray
 */
public class AverageHealthComputationSet<T extends Temporal> extends HashSet<HealthComputation<T, ?>> implements HealthComputation<T, HealthScore> {
	private final List<BiConsumer<HealthComputation<T, ?>, Temporal>> listeners;

	private T target;

	/**
	 * Creates an empty health computation set.
	 * <p>
	 * Individual health computations can be added using {@link #add(Object)}.
	 * </p>
	 */
	public AverageHealthComputationSet() {
		listeners = new ArrayList<>();
	}

	/**
	 * Returns the current target organism.
	 *
	 * @return the target organism, or null if not yet set
	 */
	public T getTarget() { return target; }

	/**
	 * Sets the target organism for all contained health computations.
	 * <p>
	 * This method propagates the target to all health computations in the set.
	 * </p>
	 *
	 * @param target the temporal entity to evaluate
	 */
	@Override
	public void setTarget(T target) {
		this.target = target;
		forEach(c -> c.setTarget(target));
	}

	/**
	 * Registers a listener to observe individual health computation evaluations.
	 * <p>
	 * The listener is called before each contained health computation evaluates,
	 * receiving both the computation and the current target.
	 * </p>
	 *
	 * @param listener callback invoked before each individual evaluation
	 */
	public void addListener(BiConsumer<HealthComputation<T, ?>, Temporal> listener) {
		listeners.add(listener);
	}

	/**
	 * Computes the average health score across all contained computations.
	 * <p>
	 * Each health computation in the set is evaluated, and the resulting
	 * scores are averaged to produce the composite score. Listeners are
	 * notified before each individual computation.
	 * </p>
	 *
	 * @return a health score representing the average of all contained evaluations
	 */
	@Override
	public HealthScore computeHealth() {
		double total = 0;

		for (HealthComputation<T, ?> hc : this) {
			listeners.forEach(l -> l.accept(hc, getTarget()));
			total += hc.computeHealth().getScore();
		}

		double score = total / size();
		return () -> score;
	}
}
