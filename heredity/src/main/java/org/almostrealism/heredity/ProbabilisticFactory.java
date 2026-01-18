/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.heredity;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.PackedCollection;

import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * A {@link Factory} implementation that selects from multiple sub-factories based on probabilities.
 *
 * <p>This class extends HashMap to store factory-to-probability mappings, allowing
 * probabilistic selection of which factory to use when constructing objects.
 * It is particularly useful in genetic algorithms for implementing probabilistic
 * selection of components based on gene values.
 *
 * <h2>Probability-Based Selection</h2>
 * <p>When {@link #construct()} is called, a random number is generated and used to
 * select one of the registered factories based on their cumulative probabilities.
 * Factories with higher probability values are more likely to be selected.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create factories for different components
 * Factory<Strategy> aggressive = () -> new AggressiveStrategy();
 * Factory<Strategy> defensive = () -> new DefensiveStrategy();
 * Factory<Strategy> balanced = () -> new BalancedStrategy();
 *
 * // Create gene with probabilities (70%, 20%, 10%)
 * Gene<PackedCollection> probabilities = HeredityFeatures.getInstance().g(0.7, 0.2, 0.1);
 *
 * // Create probabilistic factory
 * List<Factory<Strategy>> strategies = Arrays.asList(aggressive, defensive, balanced);
 * ProbabilisticFactory<Strategy> factory = new ProbabilisticFactory<>(strategies, probabilities);
 *
 * // Construct randomly selects based on probabilities
 * Strategy selected = factory.construct();  // 70% aggressive, 20% defensive, 10% balanced
 * }</pre>
 *
 * @param <V> the type of object produced by this factory
 * @see Factory
 * @see Gene
 * @see ScaleFactor
 */
public class ProbabilisticFactory<V> extends HashMap<Factory<V>, Double> implements Factory<V> {
	private Random random;

	/**
	 * Constructs an empty {@link ProbabilisticFactory}.
	 * <p>Factories and their probabilities must be added using {@link #put(Object, Object)}.
	 */
	public ProbabilisticFactory() {
		random = new Random();
	}

	/**
	 * Constructs a {@link ProbabilisticFactory} with the specified factories, where
	 * probabilities are determined by evaluating the {@link Factor}s in the specified
	 * {@link Gene}. The value 1.0 is used as an argument, making it convenient to use
	 * the {@link ScaleFactor} to specify scalar probability values.
	 *
	 * @param factories the list of factories to choose from
	 * @param probabilities a gene whose factors provide the selection probabilities
	 */
	public ProbabilisticFactory(List<? extends Factory<V>> factories, Gene<PackedCollection> probabilities) {
		for (int i = 0; i < factories.size(); i++) {
			Evaluable<PackedCollection> ev = probabilities.valueAt(i).getResultant(() -> {
				PackedCollection s = new PackedCollection(1);
				s.setMem(1.0);
				return new Provider(s);
			}).get();
			put(factories.get(i), ev.evaluate().toDouble(0));
		}
	}

	/**
	 * Constructs an object by probabilistically selecting one of the registered factories.
	 * <p>Selection is based on cumulative probability - a random value is generated and
	 * factories are tested in order until the cumulative probability exceeds the random value.
	 *
	 * @return an object constructed by the selected factory, or {@code null} if probabilities
	 *         don't sum to at least 1.0 and the random value exceeds the total
	 */
	@Override
	public V construct() {
		double r = random.nextDouble();
		double p = 0;

		for (Entry<Factory<V>, Double> e : entrySet()) {
			p += e.getValue();
			if (p > r) return e.getKey().construct();
		}

		return null;
	}

	/**
	 * Calculates the total of all probability values.
	 * <p>For proper probabilistic selection, this should sum to 1.0.
	 *
	 * @return the sum of all probability values
	 */
	protected double total() {
		double tot = 0;
		for (double d : values()) tot += d;
		return tot;
	}
}
