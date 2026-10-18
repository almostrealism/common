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
	/** The shared data source from which all projected genes in this chromosome derive their values. */
	private final PackedCollection source;

	/** The list of projected genes that derive their values from {@code source}. */
	private List<ProjectedGene> projections;
	/** Combined list of all genes in this chromosome (projected and non-projected). */
	private List<Gene<PackedCollection>> genes;
	/** Cache of consolidated gene values used during evaluation. */
	private PackedCollection consolidatedValues;
	/** The number of {@link #projections} whose values {@link #consolidatedValues} holds. */
	private int consolidatedCount;

	/**
	 * A gene spanning the factors of every gene in {@link #projections}, in order. The
	 * weights and ranges of each member gene are views into this gene's weights and
	 * ranges, so a single kernel initializes or refreshes the whole chromosome rather
	 * than one or more kernels per gene. Built on first use and rebuilt when genes have
	 * been added since.
	 */
	private ProjectedGene combined;
	/** The number of {@link #projections} that {@link #combined} spans. */
	private int combinedCount;
	/** Holds the unnormalized random weights of every gene during {@link #initWeights(LongSupplier)}. */
	private PackedCollection combinedScratch;

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
		if (projections.isEmpty()) return;

		ProjectedGene all = combinedGene();
		int sourceLength = source.getShape().getTotalSize();

		int offset = 0;
		for (ProjectedGene gene : projections) {
			int len = gene.length();
			gene.randomWeights(seeds.getAsLong(),
					combinedScratch.range(shape(len, sourceLength), offset * sourceLength));
			offset += len;
		}

		all.normalizeWeights(combinedScratch);
	}

	/**
	 * Recomputes all factor values for all projected genes.
	 * <p>This should be called after the source data has been modified.
	 *
	 * <p>The values of every gene are computed together by a single kernel. When
	 * {@link #consolidateGeneValues()} has placed the values of every gene in one buffer,
	 * the kernel writes them there directly; otherwise, as when genes were added after
	 * consolidation, it writes them to a buffer of its own and each gene's share is copied
	 * into that gene's values, which compiled computations may already refer to.</p>
	 */
	public void refreshValues() {
		if (projections.isEmpty()) return;

		ProjectedGene all = combinedGene();

		if (consolidatedValues != null && consolidatedCount == projections.size()) {
			all.replaceValues(consolidatedValues);
			all.refreshValues();
			return;
		}

		all.refreshValues();

		PackedCollection values = all.getValues();
		int offset = 0;
		for (ProjectedGene gene : projections) {
			int len = gene.length();
			gene.getValues().setFrom(0, values.range(shape(len), offset));
			offset += len;
		}
	}

	/**
	 * Returns {@link #combined}, first building it if genes have been added since it was
	 * last built. Building it copies the current weights and ranges of every gene into the
	 * combined gene and makes each gene's weights and ranges views of the copy, so values
	 * set before the genes were combined are kept, and ranges set afterwards through
	 * {@link ProjectedGene#setRange(int, double, double)} are seen by the combined gene.
	 *
	 * @return the gene spanning every gene of this chromosome
	 */
	private ProjectedGene combinedGene() {
		if (combined != null && combinedCount == projections.size()) return combined;

		int total = projections.stream().mapToInt(ProjectedGene::length).sum();
		int sourceLength = source.getShape().getTotalSize();

		ProjectedGene all = new ProjectedGene(source,
				new PackedCollection(shape(total, sourceLength).traverse(1)));
		PackedCollection weights = all.getWeights();
		PackedCollection ranges = all.getRanges();

		int offset = 0;
		for (ProjectedGene gene : projections) {
			int len = gene.length();

			PackedCollection geneWeights = weights.range(shape(len, sourceLength).traverse(1), offset * sourceLength);
			geneWeights.setFrom(0, gene.getWeights());
			gene.replaceWeights(geneWeights);

			PackedCollection geneRanges = ranges.range(shape(len, 2).traverse(1), offset * 2);
			geneRanges.setFrom(0, gene.getRanges());
			gene.replaceRanges(geneRanges);

			offset += len;
		}

		combined = all;
		combinedCount = projections.size();
		combinedScratch = new PackedCollection(shape(total, sourceLength));
		return combined;
	}

	/**
	 * Consolidates all gene value collections into a single contiguous buffer.
	 *
	 * <p>Each {@link ProjectedGene} normally allocates its own {@link PackedCollection}
	 * for factor values. When the compiled {@code Loop} scope collects arguments, each
	 * gene's values collection becomes a separate kernel argument. With many genes
	 * across the scene (effects, patterns, automation, mixdown), this can produce
	 * hundreds of arguments, slowing down scope generation and native compilation.</p>
	 *
	 * <p>This method packs all gene values into one buffer and replaces each gene's
	 * values with a view (delegate) into that buffer. Because the scope's argument
	 * deduplication resolves variables to their root delegate, all gene values from
	 * this chromosome collapse into a single kernel argument.</p>
	 *
	 * <p>After consolidation, {@link #refreshValues()} writes through the views
	 * to the shared buffer, and {@link ProjectedGene#valueAt(int)} creates sub-views
	 * that resolve to the consolidated buffer during argument collection.</p>
	 *
	 * @return the consolidated values buffer
	 */
	public PackedCollection consolidateGeneValues() {
		if (projections.isEmpty()) return null;

		int totalSize = projections.stream().mapToInt(ProjectedGene::length).sum();
		consolidatedValues = new PackedCollection(totalSize);

		int offset = 0;
		for (ProjectedGene gene : projections) {
			int len = gene.length();
			PackedCollection geneValues = gene.getValues();

			// Copy current values to the consolidated buffer
			consolidatedValues.setFrom(offset, geneValues);

			// Replace gene's values with a view of the consolidated buffer
			gene.replaceValues(consolidatedValues.range(shape(len), offset));
			offset += len;
		}

		consolidatedCount = projections.size();
		return consolidatedValues;
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
