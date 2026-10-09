/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.heredity.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.heredity.ProjectedGene;
import org.almostrealism.heredity.ScaleFactor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Tests that {@link ProjectedGene}'s device-computed projection matches a host-computed
 * reference (dot product, triangular wave, and range mapping), that weight initialization
 * normalizes each row, and that {@link ScaleFactor} scales through its producer.
 */
public class ProjectedGeneTests extends TestSuiteBase {

	/**
	 * Tests that refreshValues produces, for every factor position, the same value as the
	 * reference formula: the source-weights dot product, wrapped by a positive mod into
	 * [0, 2), mapped through a triangular wave, and scaled into the configured range.
	 */
	@Test(timeout = 120000)
	public void refreshValuesReference() {
		int factors = 4;
		int sourceLength = 12;

		double[] min = { 0.0, -1.0, 3.0, 5.0 };
		double[] max = { 1.0, 1.0, 7.0, 2.0 };

		PackedCollection source = new PackedCollection(shape(sourceLength));
		PackedCollection weights = new PackedCollection(shape(factors, sourceLength)).traverse(1);

		ProjectedGene gene = randomGene(source, weights, min, max);
		gene.refreshValues();
		assertMatchesReference(gene, source, weights, min, max);
	}

	/**
	 * Tests that the projection kernel {@link ProjectedGene} caches by shape is not
	 * carried from one compute context into another. A gene of one shape is refreshed
	 * inside a scoped data context, which is then destroyed; the same shape is then
	 * refreshed inside a second scoped context and finally under the outer context.
	 * Each refresh must compile (or reuse) a kernel belonging to a live context and
	 * produce the reference values, rather than dispatching a kernel compiled under
	 * the destroyed scope.
	 */
	@Test(timeout = 120000)
	public void refreshValuesAcrossScopedContexts() {
		double[] min = { -2.0, 0.0, 1.0 };
		double[] max = { 2.0, 4.0, -1.0 };
		int sourceLength = 9;

		for (int scope = 0; scope < 3; scope++) {
			PackedCollection source = new PackedCollection(shape(sourceLength));
			PackedCollection weights = new PackedCollection(shape(min.length, sourceLength)).traverse(1);
			ProjectedGene gene = randomGene(source, weights, min, max);

			if (scope < 2) {
				dc(() -> {
					gene.refreshValues();
					return null;
				});
			} else {
				gene.refreshValues();
			}

			assertMatchesReference(gene, source, weights, min, max);
		}
	}

	/**
	 * Tests that weight initialization, which uses a separately cached normalization
	 * kernel, also works under a second scoped context after the first one that
	 * compiled a kernel of the same shape has been destroyed.
	 */
	@Test(timeout = 120000)
	public void initWeightsAcrossScopedContexts() {
		int factors = 3;
		int sourceLength = 7;

		PackedCollection firstWeights = new PackedCollection(shape(factors, sourceLength)).traverse(1);
		ProjectedGene first = new ProjectedGene(new PackedCollection(shape(sourceLength)), firstWeights);
		dc(() -> {
			first.initWeights(7L);
			return null;
		});
		assertUnitRows(firstWeights, factors, sourceLength);

		PackedCollection secondWeights = new PackedCollection(shape(factors, sourceLength)).traverse(1);
		ProjectedGene second = new ProjectedGene(new PackedCollection(shape(sourceLength)), secondWeights);
		dc(() -> {
			second.initWeights(11L);
			return null;
		});
		assertUnitRows(secondWeights, factors, sourceLength);
	}

	/**
	 * Creates a gene over the given source and weights, both filled with uniformly
	 * random values in [-1, 1), and applies the given per-factor ranges.
	 */
	private ProjectedGene randomGene(PackedCollection source, PackedCollection weights,
									 double[] min, double[] max) {
		rand(source.getShape()).multiply(2.0).add(-1.0).into(source.traverseEach()).evaluate();
		rand(weights.getShape()).multiply(2.0).add(-1.0).into(weights.traverseEach()).evaluate();

		ProjectedGene gene = new ProjectedGene(source, weights);
		int factors = gene.length();
		for (int pos = 0; pos < factors; pos++) {
			gene.setRange(pos, min[pos], max[pos]);
		}

		return gene;
	}

	/**
	 * Asserts that every factor of the gene equals the reference formula: the
	 * source-weights dot product, wrapped by a positive mod into [0, 2), mapped
	 * through a triangular wave, and scaled into the configured range.
	 */
	private void assertMatchesReference(ProjectedGene gene, PackedCollection source,
										PackedCollection weights, double[] min, double[] max) {
		int sourceLength = source.getShape().length(0);

		for (int pos = 0; pos < gene.length(); pos++) {
			double dot = 0.0;
			for (int i = 0; i < sourceLength; i++) {
				dot += source.toDouble(i) * weights.valueAt(pos, i);
			}

			double value = ((dot % 2.0) + 2.0) % 2.0;
			double phase = value / 2.0;
			value = phase < 0.5 ? 2 * phase : 2 * (1 - phase);

			double expected = min[pos] + value * (max[pos] - min[pos]);
			double actual = gene.valueAt(pos).getResultant(null).get().evaluate().toDouble(0);
			assertEquals(expected, actual);
		}
	}

	/** Asserts that every row of the weights has unit L2 norm. */
	private void assertUnitRows(PackedCollection weights, int factors, int sourceLength) {
		for (int pos = 0; pos < factors; pos++) {
			double sumSquares = 0.0;
			for (int i = 0; i < sourceLength; i++) {
				double w = weights.valueAt(pos, i);
				sumSquares += w * w;
			}

			assertEquals(1.0, Math.sqrt(sumSquares));
		}
	}

	/**
	 * Tests that initWeights leaves every weight row with unit L2 norm.
	 */
	@Test(timeout = 120000)
	public void initWeightsNormalization() {
		int factors = 3;
		int sourceLength = 10;

		PackedCollection source = new PackedCollection(shape(sourceLength));
		PackedCollection weights = new PackedCollection(shape(factors, sourceLength)).traverse(1);

		ProjectedGene gene = new ProjectedGene(source, weights);
		gene.initWeights(42L);
		assertUnitRows(weights, factors, sourceLength);
	}

	/**
	 * Tests that combining a gene's factor producers with concat yields exactly the
	 * values obtained by evaluating each factor individually. This is the equivalence
	 * PatternLayerManager.layer relies on when it assembles a layer's automation
	 * parameters from its gene in a single computation graph rather than one
	 * evaluation per factor.
	 */
	@Test(timeout = 120000)
	public void factorConcat() {
		int factors = 6;
		int sourceLength = 8;

		PackedCollection source = new PackedCollection(shape(sourceLength));
		rand(source.getShape()).multiply(2.0).add(-1.0).into(source.traverseEach()).evaluate();

		PackedCollection weights = new PackedCollection(shape(factors, sourceLength)).traverse(1);
		rand(weights.getShape()).multiply(2.0).add(-1.0).into(weights.traverseEach()).evaluate();

		ProjectedGene gene = new ProjectedGene(source, weights);
		gene.refreshValues();

		PackedCollection combined = PackedCollection.factory().apply(factors);
		concat(shape(factors),
				IntStream.range(0, factors)
						.mapToObj(i -> gene.valueAt(i).getResultant(null))
						.toArray(Producer[]::new))
				.into(combined).evaluate();

		for (int i = 0; i < factors; i++) {
			double expected = gene.valueAt(i).getResultant(null).get().evaluate().toDouble(0);
			assertEquals(expected, combined.toDouble(i));
		}
	}

	/**
	 * Tests that a {@link ProjectedChromosome}, which initializes and refreshes all of its
	 * genes with one kernel each, produces exactly the values that the same genes produce
	 * when each is initialized and refreshed on its own with the same seeds, both when the
	 * gene values are consolidated and when a gene was added after consolidation. Half of
	 * the ranges are set before the genes are first combined and half after, so both must
	 * reach the combined computation.
	 */
	@Test(timeout = 120000)
	public void chromosomeMatchesIndividualGenes() {
		assertChromosomeMatchesIndividualGenes(new int[] { 3, 1, 5, 2 }, 0);
		assertChromosomeMatchesIndividualGenes(new int[] { 3, 1, 5, 2 }, 1);
	}

	/**
	 * Builds a chromosome with genes of the given lengths, consolidating its gene values
	 * before the last {@code addedAfterConsolidation} genes are added, and asserts that
	 * the values it computes match those of independently initialized and refreshed genes.
	 */
	private void assertChromosomeMatchesIndividualGenes(int[] lengths, int addedAfterConsolidation) {
		int sourceLength = 11;

		PackedCollection source = new PackedCollection(shape(sourceLength));
		rand(source.getShape()).multiply(4.0).add(-2.0).into(source.traverseEach()).evaluate();

		ProjectedChromosome chromosome = new ProjectedChromosome(source);
		List<ProjectedGene> combined = new ArrayList<>();
		List<ProjectedGene> individual = new ArrayList<>();
		for (int g = 0; g < lengths.length; g++) {
			if (g == lengths.length - addedAfterConsolidation) {
				chromosome.consolidateGeneValues();
			}

			combined.add(chromosome.addGene(lengths[g]));
			individual.add(new ProjectedGene(source,
					new PackedCollection(shape(lengths[g], sourceLength).traverse(1))));
		}

		if (addedAfterConsolidation == 0) {
			chromosome.consolidateGeneValues();
		}

		applyRanges(combined.subList(0, 2), 0);
		applyRanges(individual, 0);

		Random seeds = new Random(5);
		chromosome.initWeights(seeds::nextLong);
		applyRanges(combined.subList(2, lengths.length), 2);
		chromosome.refreshValues();

		Random referenceSeeds = new Random(5);
		for (ProjectedGene gene : individual) {
			gene.initWeights(referenceSeeds.nextLong());
			gene.refreshValues();
		}

		for (int g = 0; g < lengths.length; g++) {
			for (int pos = 0; pos < lengths[g]; pos++) {
				double expected = individual.get(g).valueAt(pos).getResultant(null).get().evaluate().toDouble(0);
				double actual = combined.get(g).valueAt(pos).getResultant(null).get().evaluate().toDouble(0);
				assertEquals(expected, actual);
			}
		}
	}

	/**
	 * Sets a distinct range on every factor of the given genes, so that a value computed
	 * against the wrong range cannot match. {@code first} is the index of the first gene
	 * among all the genes being configured, so that the same gene always receives the
	 * same ranges.
	 */
	private void applyRanges(List<ProjectedGene> genes, int first) {
		for (int g = 0; g < genes.size(); g++) {
			ProjectedGene gene = genes.get(g);
			for (int pos = 0; pos < gene.length(); pos++) {
				double min = -(first + g) - 0.25 * pos;
				gene.setRange(pos, min, min + 1.0 + first + g + pos);
			}
		}
	}

	/**
	 * Tests that ScaleFactor multiplies through its producer for scales established
	 * at construction.
	 */
	@Test(timeout = 120000)
	public void scaleFactor() {
		ScaleFactor factor = new ScaleFactor(0.5);
		assertEquals(0.5, factor.getScaleValue());
		assertEquals(1.5, factor.getResultant(c(3.0)).get().evaluate().toDouble(0));

		ScaleFactor quarter = new ScaleFactor(0.25);
		assertEquals(0.25, quarter.getScaleValue());
		assertEquals(2.0, quarter.getResultant(c(8.0)).get().evaluate().toDouble(0));
	}
}
