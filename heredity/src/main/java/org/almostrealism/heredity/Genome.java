/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Factor;
import io.almostrealism.uml.Plural;

import java.util.stream.IntStream;

/**
 * Represents a complete genetic representation containing multiple {@link Chromosome}s.
 *
 * <p>A {@code Genome} is the top level of the genetic hierarchy, encapsulating all the
 * genetic information needed to define an individual in an evolutionary algorithm.
 * It consists of one or more chromosomes, each containing genes with factors.
 *
 * <p>The interface provides both direct chromosome access and convenience methods for
 * accessing genes and factors at any depth using three-dimensional indexing
 * (chromosome x gene x factor).
 *
 * <h2>Genetic Hierarchy</h2>
 * <pre>
 * Genome&lt;T&gt;  &lt;-- You are here
 *   +- Contains multiple: Chromosome&lt;T&gt;
 *        +- Contains multiple: Gene&lt;T&gt;
 *             +- Contains multiple: Factor&lt;T&gt;
 * </pre>
 *
 * <h2>Common Implementations</h2>
 * <ul>
 *   <li>{@link ProjectedGenome} - A genome with projected weights for neural-style evolution</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a ProjectedGenome for parameter-based evolution
 * ProjectedGenome genome = new ProjectedGenome(100);  // 100 parameters
 * ProjectedChromosome chr = genome.addChromosome();
 * chr.addGene(10);  // Gene with 10 factors
 *
 * // Initialize and refresh
 * genome.initWeights();
 * genome.refreshValues();
 *
 * // Access chromosome
 * Chromosome<PackedCollection> firstChromosome = genome.valueAt(0);
 *
 * // Access gene directly
 * Gene<PackedCollection> gene = genome.valueAt(0, 0);  // Chromosome 0, Gene 0
 *
 * // Access factor directly
 * Factor<PackedCollection> factor = genome.valueAt(0, 0, 0);  // Chr 0, Gene 0, Factor 0
 *
 * // Create offspring with mutations
 * ProjectedGenome offspring = genome.variation(-1.0, 1.0, 0.1, () -> Math.random() * 0.2 - 0.1);
 * }</pre>
 *
 * @param <T> the type of data that chromosomes in this genome operate on
 * @see Chromosome
 * @see Gene
 * @see GenomeBreeder
 * @see ProjectedGenome
 */
public interface Genome<T> extends Plural<Chromosome<T>> {

	/**
	 * Returns the number of chromosomes in this genome.
	 *
	 * @return the number of chromosomes
	 */
	int count();

	/**
	 * Generates a unique signature string for this genome by concatenating
	 * the signatures of all contained chromosomes.
	 * <p>This can be used for identification, comparison, or hashing purposes
	 * in evolutionary algorithms.
	 *
	 * @return a string representing the combined signatures of all chromosomes
	 */
	default String signature() {
		StringBuffer buf = new StringBuffer();
		IntStream.range(0, count()).mapToObj(this::valueAt).map(Chromosome::signature).forEach(buf::append);
		return buf.toString();
	}

	/**
	 * Returns the gene at the specified chromosome and gene positions.
	 * <p>This is a convenience method for directly accessing genes within the genome
	 * using two-dimensional indexing.
	 *
	 * @param chromosome the zero-based position of the chromosome
	 * @param gene the zero-based position of the gene within the chromosome
	 * @return the gene at the specified position
	 */
	default Gene<T> valueAt(int chromosome, int gene) {
		return valueAt(chromosome).valueAt(gene);
	}

	/**
	 * Returns the factor at the specified chromosome, gene, and factor positions.
	 * <p>This is a convenience method for directly accessing factors within the genome
	 * using three-dimensional indexing.
	 *
	 * @param chromosome the zero-based position of the chromosome
	 * @param gene the zero-based position of the gene within the chromosome
	 * @param factor the zero-based position of the factor within the gene
	 * @return the factor at the specified position
	 */
	default Factor<T> valueAt(int chromosome, int gene, int factor) {
		return valueAt(chromosome).valueAt(gene, factor);
	}
}
