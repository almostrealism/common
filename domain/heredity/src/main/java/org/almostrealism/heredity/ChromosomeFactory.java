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

/**
 * A factory interface for generating {@link Chromosome} instances.
 *
 * <p>This interface provides a configurable factory pattern for creating chromosomes
 * with specified dimensions. It is useful for population initialization in genetic
 * algorithms where many chromosomes need to be generated with consistent structure.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * ChromosomeFactory<PackedCollection> factory = ...;
 *
 * // Configure chromosome dimensions
 * factory.setChromosomeSize(10, 5);  // 10 genes, 5 factors each
 *
 * // Generate chromosomes with different random seeds
 * Chromosome<PackedCollection> chr1 = factory.generateChromosome(0.1);
 * Chromosome<PackedCollection> chr2 = factory.generateChromosome(0.5);
 * Chromosome<PackedCollection> chr3 = factory.generateChromosome(0.9);
 * }</pre>
 *
 * @param <T> the type of data that chromosomes operate on
 * @see Chromosome
 */
public interface ChromosomeFactory<T> {
	/**
	 * Configures the dimensions of chromosomes that will be generated.
	 *
	 * @param genes the number of genes per chromosome
	 * @param factors the number of factors per gene
	 * @return this factory for method chaining
	 */
	ChromosomeFactory<T> setChromosomeSize(int genes, int factors);

	/**
	 * Generates a new chromosome using the specified argument.
	 * <p>The argument is typically used as a seed or parameter that influences
	 * the generated chromosome's initial values.
	 *
	 * @param arg a parameter influencing chromosome generation (often a random seed or diversity factor)
	 * @return a newly generated chromosome
	 */
	Chromosome<T> generateChromosome(double arg);
}
