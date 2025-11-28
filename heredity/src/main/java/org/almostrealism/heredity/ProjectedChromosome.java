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

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * A {@link Chromosome} implementation containing {@link ProjectedGene}s that share a common source.
 *
 * <p>This class manages a collection of genes where all genes project from the same source data.
 * It supports both regular {@link ProjectedGene}s and {@link ChoiceGene}s that wrap projected genes
 * to provide discrete choice selection.
 *
 * <p>The chromosome maintains two lists internally:
 * <ul>
 *   <li><b>projections</b> - All ProjectedGene instances (including those wrapped by ChoiceGene)</li>
 *   <li><b>genes</b> - The genes exposed through the Chromosome interface (may include ChoiceGenes)</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create source data
 * PackedCollection source = new PackedCollection(100);
 *
 * // Create chromosome
 * ProjectedChromosome chromosome = new ProjectedChromosome(source);
 *
 * // Add genes with different factor counts
 * ProjectedGene gene1 = chromosome.addGene(5);   // 5 factors
 * ProjectedGene gene2 = chromosome.addGene(10);  // 10 factors
 *
 * // Add a choice gene for discrete selection
 * PackedCollection choices = new PackedCollection(3);  // 3 choices
 * ChoiceGene choiceGene = chromosome.addChoiceGene(choices, 1);
 *
 * // Initialize weights and compute values
 * Random random = new Random(42);
 * chromosome.initWeights(random::nextLong);
 * chromosome.refreshValues();
 *
 * // Access genes
 * Gene<PackedCollection> first = chromosome.valueAt(0);
 * }</pre>
 *
 * @see ProjectedGene
 * @see ProjectedGenome
 * @see ChoiceGene
 * @see Chromosome
 */
public class ProjectedChromosome implements Chromosome<PackedCollection>, CollectionFeatures {
	private final PackedCollection source;

	private List<ProjectedGene> projections;
	private List<Gene<PackedCollection>> genes;

	/**
	 * Constructs a new {@code ProjectedChromosome} with the specified source data.
	 *
	 * @param source the source data that all genes in this chromosome will project from
	 */
	public ProjectedChromosome(PackedCollection source) {
		this.source = source;
		this.projections = new ArrayList<>();
		this.genes = new ArrayList<>();
	}

	/**
	 * Initializes weights for all projected genes using seeds from the supplier.
	 * <p>Each gene receives a unique seed obtained by calling the supplier.
	 *
	 * @param seeds a supplier providing random seeds for each gene
	 */
	public void initWeights(LongSupplier seeds) {
		for (ProjectedGene gene : projections) {
			gene.initWeights(seeds.getAsLong());
		}
	}

	/**
	 * Recomputes all factor values for all projected genes.
	 * <p>This should be called after the source data has been modified.
	 */
	public void refreshValues() {
		for (ProjectedGene gene : projections) {
			gene.refreshValues();
		}
	}

	/**
	 * Creates a new ProjectedGene with the specified number of factors.
	 * <p>The gene is added to the projections list but not to the exposed genes list.
	 * This is used internally by {@link #addGene(int)} and {@link #addChoiceGene(PackedCollection, int)}.
	 *
	 * @param length the number of factors in the new gene
	 * @return the newly created ProjectedGene
	 */
	protected ProjectedGene createGene(int length) {
		int input = source.getShape().getTotalSize();
		PackedCollection weight = new PackedCollection(shape(length, input).traverse(1));
		ProjectedGene gene = new ProjectedGene(source, weight);
		projections.add(gene);
		return gene;
	}

	/**
	 * Creates and adds a new {@link ProjectedGene} to this chromosome.
	 *
	 * @param length the number of factors in the new gene
	 * @return the newly created and added ProjectedGene
	 */
	public ProjectedGene addGene(int length) {
		ProjectedGene gene = createGene(length);
		genes.add(gene);
		return gene;
	}

	/**
	 * Creates and adds a new {@link ChoiceGene} that wraps a ProjectedGene.
	 * <p>The ChoiceGene maps continuous values to discrete choices from the provided collection.
	 *
	 * @param choices the collection of discrete choices
	 * @param length the number of factors in the underlying projected gene
	 * @return the newly created and added ChoiceGene
	 */
	public ChoiceGene addChoiceGene(PackedCollection choices, int length) {
		ChoiceGene gene = new ChoiceGene(createGene(length), choices);
		genes.add(gene);
		return gene;
	}

	/**
	 * Removes the gene at the specified index from this chromosome.
	 *
	 * @param index the zero-based index of the gene to remove
	 */
	public void removeGene(int index) {
		genes.remove(index);
	}

	/**
	 * Removes all genes from this chromosome.
	 */
	public void removeAllGenes() { genes.clear(); }

	/**
	 * Returns the gene at the specified position.
	 *
	 * @param pos the zero-based position of the gene
	 * @return the gene at that position
	 */
	@Override
	public Gene<PackedCollection> valueAt(int pos) {
		return genes.get(pos);
	}

	/**
	 * Returns the number of genes in this chromosome.
	 *
	 * @return the gene count
	 */
	@Override
	public int length() { return genes.size(); }
}
