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

import org.almostrealism.heredity.Genome;
import org.almostrealism.time.Temporal;

import java.util.List;

/**
 * Represents a population of genomes in an evolutionary algorithm.
 * <p>
 * A {@code Population} manages a collection of {@link Genome} instances that represent
 * individual organisms in the evolutionary process. It provides methods to access genomes,
 * enable them for fitness evaluation, and manage the active genome state.
 * </p>
 *
 * <h2>Genome Activation</h2>
 * <p>
 * The population maintains an "active genome" concept where one genome at a time can be
 * enabled for fitness evaluation. This is crucial for systems where genome activation
 * involves resource allocation (e.g., instantiating neural networks).
 * </p>
 *
 * <h2>Integration with PopulationOptimizer</h2>
 * <p>
 * The {@link PopulationOptimizer} iterates through populations, evaluating each genome's
 * fitness and breeding the best performers to create new generations.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class NeuralNetworkPopulation implements Population<Gene, NeuralNetwork> {
 *     private List<Genome<Gene>> genomes;
 *     private NeuralNetwork activeNetwork;
 *
 *     public List<Genome<Gene>> getGenomes() { return genomes; }
 *
 *     public int size() { return genomes.size(); }
 *
 *     public NeuralNetwork enableGenome(int index) {
 *         disableGenome();
 *         activeNetwork = buildNetworkFromGenome(genomes.get(index));
 *         return activeNetwork;
 *     }
 *
 *     public void disableGenome() {
 *         if (activeNetwork != null) {
 *             activeNetwork.destroy();
 *             activeNetwork = null;
 *         }
 *     }
 * }
 * }</pre>
 *
 * @param <G> the gene type stored in genomes
 * @param <O> the organism type produced when a genome is enabled
 *
 * @see Genome
 * @see PopulationOptimizer
 *
 * @author Michael Murray
 */
public interface Population<G, O extends Temporal> {

	/**
	 * Returns the list of genomes in this population.
	 * <p>
	 * The returned list is typically mutable, allowing the optimizer to
	 * modify the population during breeding operations.
	 * </p>
	 *
	 * @return the list of genomes; never null
	 */
	List<Genome<G>> getGenomes();

	/**
	 * Returns the number of genomes in this population.
	 *
	 * @return the population size
	 */
	int size();

	/**
	 * Enables the genome at the specified index for fitness evaluation.
	 * <p>
	 * This method activates a genome, typically by instantiating the organism
	 * it encodes (e.g., building a neural network from its genetic specification).
	 * Only one genome should be active at a time.
	 * </p>
	 *
	 * @param index the index of the genome to enable
	 * @return the organism instance created from the genome
	 * @throws IndexOutOfBoundsException if index is out of range
	 */
	O enableGenome(int index);

	/**
	 * Disables the currently active genome.
	 * <p>
	 * This method releases resources associated with the active organism.
	 * Should be called after fitness evaluation is complete.
	 * </p>
	 */
	void disableGenome();
}
