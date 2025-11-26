/**
 * Provides a genetic algorithm and evolutionary computation framework built on the
 * Producer/Factor pattern.
 *
 * <p>This package enables encoding data as genes, chromosomes, and genomes that can
 * evolve through breeding and mutation operations. It forms the foundation for
 * optimization algorithms that use evolutionary strategies.
 *
 * <h2>Genetic Hierarchy</h2>
 * <p>The core data structures follow a hierarchical organization:
 * <pre>
 * Genome&lt;T&gt;
 *   +- Contains multiple: Chromosome&lt;T&gt;
 *        +- Contains multiple: Gene&lt;T&gt;
 *             +- Contains multiple: Factor&lt;T&gt;
 * </pre>
 *
 * <h2>Core Interfaces</h2>
 * <ul>
 *   <li>{@link org.almostrealism.heredity.Gene} - Collection of factors that represent
 *       a single genetic unit</li>
 *   <li>{@link org.almostrealism.heredity.Chromosome} - Collection of genes that form
 *       a logical grouping</li>
 *   <li>{@link org.almostrealism.heredity.Genome} - Complete genetic representation
 *       containing multiple chromosomes</li>
 * </ul>
 *
 * <h2>Gene Types</h2>
 * <ul>
 *   <li>{@link org.almostrealism.heredity.TransformableGene} - Abstract gene supporting
 *       value transformations</li>
 *   <li>{@link org.almostrealism.heredity.ProjectedGene} - Gene that projects source
 *       data through weighted transformations</li>
 *   <li>{@link org.almostrealism.heredity.ChoiceGene} - Gene that maps continuous
 *       values to discrete choices</li>
 * </ul>
 *
 * <h2>Breeding Operations</h2>
 * <ul>
 *   <li>{@link org.almostrealism.heredity.ChromosomeBreeder} - Combines two chromosomes
 *       to produce offspring</li>
 *   <li>{@link org.almostrealism.heredity.GenomeBreeder} - Combines two genomes to
 *       produce offspring</li>
 *   <li>{@link org.almostrealism.heredity.Breeders} - Utility methods for breeding
 *       operations like bounded perturbation</li>
 * </ul>
 *
 * <h2>Factor Types</h2>
 * <ul>
 *   <li>{@link org.almostrealism.heredity.ScaleFactor} - Multiplies input by a scalar value</li>
 *   <li>{@link org.almostrealism.heredity.IdentityFactor} - Returns input unchanged</li>
 *   <li>{@link org.almostrealism.heredity.CombinedFactor} - Chains two factors together</li>
 *   <li>{@link org.almostrealism.heredity.TemporalFactor} - Factor that evolves over time</li>
 * </ul>
 *
 * <h2>Temporal Integration</h2>
 * <p>The package integrates with the time module through:
 * <ul>
 *   <li>{@link org.almostrealism.heredity.Cellular} - Interface for cell-based computation</li>
 *   <li>{@link org.almostrealism.heredity.TemporalCellular} - Combines temporal and cellular behaviors</li>
 *   <li>{@link org.almostrealism.heredity.CellularTemporalFactor} - Temporal factors with cellular lifecycle</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create genes from scalar values
 * HeredityFeatures features = HeredityFeatures.getInstance();
 * Gene<PackedCollection<?>> gene1 = features.g(0.1, 0.5, 0.9);
 * Gene<PackedCollection<?>> gene2 = features.g(-1.0, 0.0, 1.0);
 *
 * // Create chromosome from genes
 * Chromosome<PackedCollection<?>> chromosome = features.c(gene1, gene2);
 *
 * // Access factors
 * Factor<PackedCollection<?>> factor = chromosome.valueAt(0, 1);
 * }</pre>
 *
 * @see org.almostrealism.heredity.Gene
 * @see org.almostrealism.heredity.Chromosome
 * @see org.almostrealism.heredity.Genome
 * @see org.almostrealism.heredity.HeredityFeatures
 */
package org.almostrealism.heredity;