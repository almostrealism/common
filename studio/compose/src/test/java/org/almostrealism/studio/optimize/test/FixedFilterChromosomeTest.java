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

package org.almostrealism.studio.optimize.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.studio.optimize.FixedFilterChromosome;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Behavioral tests for {@link FixedFilterChromosome}, the decorator that reads a source
 * chromosome's gene values as bandpass filter cutoff frequencies.
 *
 * <p>The source genes project constant source data through random weights and a
 * triangular wave into each gene's configured output range, so the exact projected
 * value is not predictable. The contract that is predictable — and the one these tests
 * pin — is that each cutoff frequency equals twenty kilohertz times a value drawn from
 * the gene's range, so restricting that range with
 * {@link FixedFilterChromosome#setHighPassRange(double, double)} /
 * {@link FixedFilterChromosome#setLowPassRange(double, double)} must confine the emitted
 * frequency to that range.</p>
 */
public class FixedFilterChromosomeTest extends TestSuiteBase implements CollectionFeatures {
	/** The audio sample rate used for the filters under test. */
	private static final int SAMPLE_RATE = 44100;

	/** The maximum frequency the chromosome scales gene values to, in Hz. */
	private static final double MAX_FREQUENCY = 20000;

	/** Tolerance for floating-point range checks, in Hz. */
	private static final double EPS = 1.0e-3;

	/**
	 * Builds a source chromosome with the given number of two-slot genes, with weights
	 * initialized from a fixed seed and values refreshed so projected values are defined.
	 *
	 * @param genes the number of genes to add
	 * @return the initialized source chromosome
	 */
	private ProjectedChromosome source(int genes) {
		ProjectedChromosome source = new ProjectedChromosome(new PackedCollection(16).fill(0.5));
		for (int i = 0; i < genes; i++) {
			source.addGene(FixedFilterChromosome.SIZE);
		}
		source.initWeights(new Random(42)::nextLong);
		source.refreshValues();
		return source;
	}

	/**
	 * Evaluates a cutoff-frequency producer to a single {@code double}.
	 *
	 * @param producer the frequency producer
	 * @return the emitted frequency in Hz
	 */
	private double frequency(Producer<PackedCollection> producer) {
		return producer.get().evaluate().toDouble(0);
	}

	/** The decorator's length must match its source and each filter gene must hold a single factor. */
	@Test(timeout = 120000)
	public void lengthAndGeneStructureMatchSource() {
		FixedFilterChromosome chromosome = new FixedFilterChromosome(source(3), SAMPLE_RATE);

		Assert.assertEquals(3, chromosome.length());

		Gene<PackedCollection> gene = chromosome.valueAt(0);
		Assert.assertEquals(1, gene.length());
	}

	/** Without a restricted range, each cutoff frequency falls within the full 0–20 kHz span. */
	@Test(timeout = 120000)
	public void defaultCutoffsAreWithinFullSpectrum() {
		FixedFilterChromosome chromosome = new FixedFilterChromosome(source(2), SAMPLE_RATE);

		double high = frequency(chromosome.highPassFrequency(0));
		double low = frequency(chromosome.lowPassFrequency(0));

		Assert.assertTrue("high-pass cutoff must be in [0, 20kHz]: " + high,
				high >= -EPS && high <= MAX_FREQUENCY + EPS);
		Assert.assertTrue("low-pass cutoff must be in [0, 20kHz]: " + low,
				low >= -EPS && low <= MAX_FREQUENCY + EPS);
	}

	/** Restricting the high-pass and low-pass ranges confines each emitted cutoff to that range. */
	@Test(timeout = 120000)
	public void restrictedRangesConfineCutoffs() {
		ProjectedChromosome source = source(2);
		FixedFilterChromosome chromosome = new FixedFilterChromosome(source, SAMPLE_RATE);

		chromosome.setHighPassRange(100, 200);
		chromosome.setLowPassRange(3000, 5000);
		source.refreshValues();

		for (int pos = 0; pos < source.length(); pos++) {
			double high = frequency(chromosome.highPassFrequency(pos));
			double low = frequency(chromosome.lowPassFrequency(pos));

			Assert.assertTrue("high-pass cutoff must be in [100, 200]: " + high,
					high >= 100 - EPS && high <= 200 + EPS);
			Assert.assertTrue("low-pass cutoff must be in [3000, 5000]: " + low,
					low >= 3000 - EPS && low <= 5000 + EPS);
		}
	}
}
