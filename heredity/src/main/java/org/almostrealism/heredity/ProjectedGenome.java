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

import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A {@link Genome} implementation that uses a parameter collection to drive gene values.
 *
 * <p>This class provides a genome where all genetic information is derived from a single
 * parameter collection. Changes to the parameters propagate through the projection weights
 * to produce different factor values in the genes. This design enables continuous optimization
 * over the parameter space rather than direct manipulation of individual genes.
 *
 * <h2>Architecture</h2>
 * <pre>
 * Parameters (PackedCollection)
 *     │
 *     ▼ (shared by all chromosomes)
 * ProjectedChromosome(s)
 *     │
 *     ▼ (each gene has projection weights)
 * ProjectedGene(s)
 *     │
 *     ▼ (computed via weighted projection)
 * Factor values
 * </pre>
 *
 * <h2>Mutation via Variation</h2>
 * <p>The {@link #variation(double, double, double, DoubleSupplier)} method creates offspring
 * by perturbing the parameter values. This is the primary mechanism for evolution:
 * <ul>
 *   <li>Parameters are perturbed with a given probability (mutation rate)</li>
 *   <li>Perturbation magnitude is provided by a delta supplier</li>
 *   <li>Values are clamped to specified bounds [min, max]</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create genome with 100 parameters
 * ProjectedGenome genome = new ProjectedGenome(100);
 *
 * // Add chromosomes and genes
 * ProjectedChromosome layer1 = genome.addChromosome();
 * layer1.addGene(10);  // 10 factors
 * layer1.addGene(10);  // 10 factors
 *
 * ProjectedChromosome layer2 = genome.addChromosome();
 * layer2.addGene(5);   // 5 factors
 *
 * // Initialize weights deterministically and compute values
 * genome.initWeights();
 * genome.refreshValues();
 *
 * // Access genetic information
 * Factor<PackedCollection<?>> factor = genome.valueAt(0, 0, 0);
 *
 * // Create offspring with 10% mutation rate
 * ProjectedGenome offspring = genome.variation(
 *     -1.0,                           // min parameter value
 *     1.0,                            // max parameter value
 *     0.1,                            // 10% mutation rate
 *     () -> Math.random() * 0.2 - 0.1 // delta in [-0.1, 0.1]
 * );
 *
 * // Create random genome (for population initialization)
 * ProjectedGenome randomGenome = genome.random();
 * }</pre>
 *
 * @see ProjectedChromosome
 * @see ProjectedGene
 * @see Genome
 */
public class ProjectedGenome implements Genome<PackedCollection<?>> {
	/**
	 * Default seed used for deterministic weight initialization.
	 */
	public static final long initialSeed = 0xDEAD;

	private PackedCollection<?> parameters;
	private List<ProjectedChromosome> chromosomes;

	/**
	 * Constructs a new {@code ProjectedGenome} with the specified number of parameters.
	 * <p>The parameter collection is initialized with zeros.
	 *
	 * @param parameters the number of parameters in the genome
	 */
	public ProjectedGenome(int parameters) {
		this(new PackedCollection(parameters));
	}

	/**
	 * Constructs a new {@code ProjectedGenome} using the provided parameter collection.
	 *
	 * @param parameters the parameter collection that drives gene values
	 */
	public ProjectedGenome(PackedCollection<?> parameters) {
		this.parameters = parameters;
		this.chromosomes = new ArrayList<>();
	}

	/**
	 * Initializes weights for all chromosomes using the default seed.
	 * <p>This provides deterministic initialization for reproducible experiments.
	 */
	public void initWeights() {
		Random random = new Random(initialSeed);
		initWeights(() -> new Random(random.nextLong()));
	}

	/**
	 * Initializes weights for all chromosomes using Random instances from the supplier.
	 * <p>Each chromosome receives a fresh Random for seed generation.
	 *
	 * @param seeds a supplier that provides Random instances for each chromosome
	 */
	public void initWeights(Supplier<Random> seeds) {
		for (ProjectedChromosome chromosome : chromosomes) {
			chromosome.initWeights(seeds.get()::nextLong);
		}
	}

	/**
	 * Recomputes all factor values by projecting current parameters through all genes.
	 * <p>This should be called after parameters have been modified.
	 */
	public void refreshValues() {
		for (ProjectedChromosome chromosome : chromosomes) {
			chromosome.refreshValues();
		}
	}

	/**
	 * Creates and adds a new {@link ProjectedChromosome} to this genome.
	 * <p>The new chromosome shares the same parameter collection as all other chromosomes.
	 *
	 * @return the newly created and added chromosome
	 */
	public ProjectedChromosome addChromosome() {
		ProjectedChromosome chromosome = new ProjectedChromosome(parameters);
		chromosomes.add(chromosome);
		return chromosome;
	}

	/**
	 * Removes the chromosome at the specified index.
	 *
	 * @param index the zero-based index of the chromosome to remove
	 */
	public void removeChromosome(int index) {
		chromosomes.remove(index);
	}

	/**
	 * Returns the parameter collection that drives this genome.
	 *
	 * @return the parameters
	 */
	public PackedCollection<?> getParameters() { return parameters; }

	/**
	 * Assigns new parameter values from the provided collection.
	 * <p>The collection must have the same total size as the current parameters.
	 * After assignment, weights are reinitialized and values are refreshed.
	 *
	 * @param parameters the new parameter values
	 * @throws IllegalArgumentException if the parameter sizes don't match
	 */
	public void assignTo(PackedCollection<?> parameters) {
		if (parameters.getShape().getTotalSize() != this.parameters.getShape().getTotalSize())
			throw new IllegalArgumentException();

		this.parameters.setMem(0, parameters);

		initWeights();
		refreshValues();
	}

	/**
	 * Returns the chromosome at the specified position.
	 *
	 * @param pos the zero-based position of the chromosome
	 * @return the chromosome at that position
	 */
	@Override
	public Chromosome<PackedCollection<?>> valueAt(int pos) {
		return chromosomes.get(pos);
	}

	/**
	 * Returns the number of chromosomes in this genome.
	 *
	 * @return the chromosome count
	 */
	@Override
	public int count() {
		return chromosomes.size();
	}

	/**
	 * Creates a new genome with mutated parameters.
	 * <p>Each parameter has a probability of {@code rate} to be mutated.
	 * When mutated, the parameter is perturbed by a value from {@code delta}
	 * and clamped to the range [min, max].
	 *
	 * <p>Note: The returned genome has the same parameter structure but does not
	 * copy the chromosome/gene structure. The caller must set up chromosomes
	 * on the offspring genome separately.
	 *
	 * @param min the minimum allowed parameter value
	 * @param max the maximum allowed parameter value
	 * @param rate the probability of mutation for each parameter (0.0 to 1.0)
	 * @param delta a supplier that provides mutation deltas
	 * @return a new ProjectedGenome with mutated parameters
	 */
	public ProjectedGenome variation(double min, double max, double rate, DoubleSupplier delta) {
		PackedCollection<?> variation = new PackedCollection<>(parameters.getShape());
		variation.fill(pos -> {
			double v = parameters.valueAt(pos);

			if (Math.random() < rate) {
				return Math.min(max, Math.max(min, v + delta.getAsDouble()));
			} else {
				return v;
			}
		});

		return new ProjectedGenome(variation);
	}

	/**
	 * Creates a new genome with random parameter values.
	 * <p>This is useful for initializing a population with diverse individuals.
	 *
	 * <p>Note: The returned genome has random parameters but no chromosomes.
	 * The caller must set up chromosomes on the new genome separately.
	 *
	 * @return a new ProjectedGenome with random parameters
	 */
	public ProjectedGenome random() {
		return new ProjectedGenome(new PackedCollection<>(parameters.getShape()).randFill());
	}

	/**
	 * Generates a unique signature for this genome based on its parameters.
	 * <p>The signature is created by converting each parameter to its hex representation
	 * and joining them with colons.
	 *
	 * @return a string signature representing this genome's parameters
	 */
	@Override
	public String signature() {
		return parameters.doubleStream()
				.mapToObj(Double::toHexString)
				.collect(Collectors.joining(":"));
	}
}
