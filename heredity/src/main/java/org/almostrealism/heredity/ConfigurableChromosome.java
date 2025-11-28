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

package org.almostrealism.heredity;

import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * An abstract {@link Chromosome} that provides access to gene parameters and breeding operations.
 *
 * <p>This class extends the basic Chromosome interface to support:
 * <ul>
 *   <li>Access to underlying parameters for each gene</li>
 *   <li>Access to parameter ranges for validation and mutation</li>
 *   <li>A breeding strategy specific to this chromosome type</li>
 * </ul>
 *
 * <p>Implementations can use this to create chromosomes that are self-documenting
 * about their parameter structure and can provide their own breeding logic.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * ConfigurableChromosome chromosome = ...;
 *
 * // Access gene parameters
 * PackedCollection gene0Params = chromosome.getParameters(0);
 *
 * // Get valid ranges for gene parameters
 * PackedCollection gene0Ranges = chromosome.getParameterRanges(0);
 *
 * // Get the appropriate breeder for this chromosome type
 * ChromosomeBreeder<PackedCollection> breeder = chromosome.getBreeder();
 * Chromosome<PackedCollection> offspring = breeder.combine(chromosome, otherChromosome);
 * }</pre>
 *
 * @see Chromosome
 * @see GeneParameters
 * @see ChromosomeBreeder
 */
public abstract class ConfigurableChromosome implements Chromosome<PackedCollection> {
	/**
	 * Returns the underlying parameters for the gene at the specified index.
	 *
	 * @param gene the zero-based index of the gene
	 * @return the parameter collection for that gene
	 */
	public abstract PackedCollection getParameters(int gene);

	/**
	 * Returns the valid parameter ranges for the gene at the specified index.
	 *
	 * @param gene the zero-based index of the gene
	 * @return the parameter ranges for that gene
	 */
	public abstract PackedCollection getParameterRanges(int gene);

	/**
	 * Returns a breeder suitable for combining this chromosome with another.
	 * <p>The returned breeder understands the structure of this chromosome type
	 * and can perform appropriate crossover operations.
	 *
	 * @return a chromosome breeder for this chromosome type
	 */
	public abstract ChromosomeBreeder<PackedCollection> getBreeder();
}
