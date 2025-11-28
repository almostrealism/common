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

package org.almostrealism.heredity;
import org.almostrealism.collect.PackedCollection;

/**
 * A functional interface for combining two {@link Genome}s to produce offspring.
 *
 * <p>The {@code GenomeBreeder} defines the crossover operation at the genome level in
 * genetic algorithms. It operates on complete genetic representations and determines
 * how chromosomes from two parent genomes are combined to create a new genome.
 *
 * <p>Implementations may use different strategies for combining genomes:
 * <ul>
 *   <li>Chromosome-level crossover - swap entire chromosomes between parents</li>
 *   <li>Delegate to {@link ChromosomeBreeder} - breed corresponding chromosomes</li>
 *   <li>Custom mixing - apply different strategies to different chromosomes</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Define a genome breeder that uses a chromosome breeder for each chromosome
 * ChromosomeBreeder<PackedCollection> chrBreeder = ...;
 *
 * GenomeBreeder<PackedCollection> breeder = (g1, g2) -> {
 *     // Breed each corresponding chromosome pair
 *     int count = Math.min(g1.count(), g2.count());
 *     ProjectedGenome offspring = new ProjectedGenome(parameterCount);
 *
 *     for (int i = 0; i < count; i++) {
 *         Chromosome<?> chr = chrBreeder.combine(g1.valueAt(i), g2.valueAt(i));
 *         // Add chromosome to offspring...
 *     }
 *
 *     return offspring;
 * };
 *
 * // Use the breeder
 * Genome offspring = breeder.combine(parent1, parent2);
 * }</pre>
 *
 * @param <T> the type of data that genomes operate on
 * @see Genome
 * @see ChromosomeBreeder
 * @see Breeders
 */
@FunctionalInterface
public interface GenomeBreeder<T> {
	/**
	 * Combines two parent genomes to produce an offspring genome.
	 *
	 * @param g1 the first parent genome
	 * @param g2 the second parent genome
	 * @return a new genome representing the offspring
	 */
	Genome combine(Genome<T> g1, Genome<T> g2);
}
