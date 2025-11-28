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

package org.almostrealism.heredity;
import org.almostrealism.collect.PackedCollection;

/**
 * A functional interface for combining two {@link Chromosome}s to produce offspring.
 *
 * <p>The {@code ChromosomeBreeder} defines the crossover operation in genetic algorithms.
 * Implementations determine how genetic material from two parent chromosomes is combined
 * to create a new chromosome. Common strategies include:
 * <ul>
 *   <li>Single-point crossover - genes before a random point from parent 1, after from parent 2</li>
 *   <li>Two-point crossover - genes between two random points swapped between parents</li>
 *   <li>Uniform crossover - each gene independently selected from either parent</li>
 *   <li>Averaging - factor values averaged between parents</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Define a simple averaging breeder
 * ChromosomeBreeder<PackedCollection> breeder = (c1, c2) -> {
 *     // Custom crossover logic
 *     int length = Math.min(c1.length(), c2.length());
 *     Gene<PackedCollection>[] genes = new Gene[length];
 *
 *     for (int i = 0; i < length; i++) {
 *         // Select gene from either parent
 *         genes[i] = Math.random() < 0.5 ? c1.valueAt(i) : c2.valueAt(i);
 *     }
 *
 *     return HeredityFeatures.getInstance().c(genes);
 * };
 *
 * // Use the breeder
 * Chromosome<PackedCollection> offspring = breeder.combine(parent1, parent2);
 * }</pre>
 *
 * @param <T> the type of data that chromosomes operate on
 * @see Chromosome
 * @see GenomeBreeder
 * @see Breeders
 */
@FunctionalInterface
public interface ChromosomeBreeder<T> {
	/**
	 * Combines two parent chromosomes to produce an offspring chromosome.
	 *
	 * @param c1 the first parent chromosome
	 * @param c2 the second parent chromosome
	 * @return a new chromosome representing the offspring
	 */
	Chromosome<T> combine(Chromosome<T> c1, Chromosome<T> c2);
}
