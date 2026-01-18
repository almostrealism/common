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

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Plural;

import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Represents a collection of {@link Gene}s that form a logical grouping within a {@link Genome}.
 *
 * <p>A {@code Chromosome} is an intermediate level in the genetic hierarchy, containing multiple
 * genes that together represent a related set of genetic information. In evolutionary algorithms,
 * chromosomes are typically the unit of crossover operations during breeding.
 *
 * <p>The interface provides both direct gene access and convenience methods for accessing factors
 * within genes, supporting the common pattern of treating the chromosome as a two-dimensional
 * structure (genes x factors).
 *
 * <h2>Genetic Hierarchy</h2>
 * <pre>
 * Genome&lt;T&gt;
 *   +- Contains multiple: Chromosome&lt;T&gt;  &lt;-- You are here
 *        +- Contains multiple: Gene&lt;T&gt;
 *             +- Contains multiple: Factor&lt;T&gt;
 * </pre>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create chromosome from genes
 * HeredityFeatures features = HeredityFeatures.getInstance();
 * Gene<PackedCollection> gene1 = features.g(0.1, 0.5);
 * Gene<PackedCollection> gene2 = features.g(0.2, 0.8);
 * Chromosome<PackedCollection> chromosome = features.c(gene1, gene2);
 *
 * // Access genes
 * Gene<PackedCollection> firstGene = chromosome.valueAt(0);
 *
 * // Access factor directly
 * Factor<PackedCollection> factor = chromosome.valueAt(1, 0);  // Gene 1, Factor 0
 *
 * // Iterate over genes
 * chromosome.forEach(gene -> System.out.println(gene.signature()));
 * }</pre>
 *
 * @param <T> the type of data that genes in this chromosome operate on
 * @see Gene
 * @see Genome
 * @see ChromosomeBreeder
 * @see HeredityFeatures#c(Gene[])
 */
public interface Chromosome<T> extends Plural<Gene<T>>, IntFunction<Gene<T>> {
	/**
	 * Returns the number of genes in this chromosome.
	 *
	 * @return the number of genes
	 */
	int length();

	/**
	 * Returns the gene at the specified position.
	 * <p>This is the {@link IntFunction} implementation that delegates to {@link #valueAt(int)}.
	 *
	 * @param pos the zero-based position of the gene to retrieve
	 * @return the gene at the specified position
	 */
	@Override
	default Gene<T> apply(int pos) {
		return valueAt(pos);
	}

	/**
	 * Returns the factor at the specified gene and factor positions.
	 * <p>This is a convenience method for directly accessing factors within the chromosome
	 * using two-dimensional indexing.
	 *
	 * @param gene the zero-based position of the gene
	 * @param factor the zero-based position of the factor within the gene
	 * @return the factor at the specified position
	 */
	default Factor<T> valueAt(int gene, int factor) {
		return valueAt(gene).valueAt(factor);
	}

	/**
	 * Performs the given action for each gene in this chromosome.
	 *
	 * @param consumer the action to be performed for each gene
	 */
	default void forEach(Consumer<Gene<T>> consumer) {
		IntStream.range(0, length()).mapToObj(this::valueAt).forEach(consumer);
	}

	/**
	 * Applies the factor at the specified gene and factor positions to the input producer.
	 * <p>This is a convenience method that combines factor lookup and application.
	 *
	 * @param gene the zero-based position of the gene
	 * @param factor the zero-based position of the factor within the gene
	 * @param input the input producer to transform
	 * @return a producer representing the transformed result
	 */
	default Producer<T> getResultant(int gene, int factor, Producer<T> input) {
		return valueAt(gene, factor).getResultant(input);
	}

	/**
	 * Generates a unique signature string for this chromosome by concatenating
	 * the signatures of all contained genes.
	 * <p>This can be used for identification, comparison, or hashing purposes
	 * in evolutionary algorithms.
	 *
	 * @return a string representing the combined signatures of all genes
	 */
	default String signature() {
		StringBuffer buf = new StringBuffer();
		IntStream.range(0, length()).mapToObj(this::valueAt).map(Gene::signature).forEach(buf::append);
		return buf.toString();
	}
}
