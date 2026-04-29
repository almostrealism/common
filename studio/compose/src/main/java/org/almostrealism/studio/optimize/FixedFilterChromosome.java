/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.studio.optimize;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.ProjectedGene;

/**
 * A {@link Chromosome} decorator that interprets gene values as bandpass filter parameters.
 * Each gene in the underlying source chromosome is treated as a high-pass and low-pass
 * frequency pair; the resulting {@link Gene} returns an {@link AudioPassFilter} chain
 * that applies both filters sequentially.
 *
 * <p>The high-pass cutoff is determined by gene slot 0 and the low-pass cutoff by slot 1,
 * both scaled to the range {@code [0, maxFrequency]} (20 kHz).</p>
 */
public class FixedFilterChromosome implements Chromosome<PackedCollection>, CellFeatures {
	/** The number of gene slots per filter: one for high-pass frequency and one for low-pass. */
	public static final int SIZE = 2;

	/** Default resonance (Q factor) applied to all filters produced by this chromosome. */
	public static double defaultResonance = 0.2;

	/** Maximum frequency in Hz that the gene values are scaled to. */
	private static final double maxFrequency = 20000;

	/** The underlying chromosome providing raw gene values in the unit range. */
	private final Chromosome<PackedCollection> source;

	/** Audio sample rate in Hz used when constructing the filter processors. */
	private final int sampleRate;

	/**
	 * Creates a {@code FixedFilterChromosome} wrapping the given source chromosome.
	 *
	 * @param source     the chromosome providing raw frequency gene values in the unit range
	 * @param sampleRate the audio sample rate in Hz
	 */
	public FixedFilterChromosome(Chromosome<PackedCollection> source, int sampleRate) {
		this.source = source;
		this.sampleRate = sampleRate;
	}

	@Override
	public int length() {
		return source.length();
	}

	@Override
	public Gene<PackedCollection> valueAt(int pos) {
		return new FixedFilterGene(pos);
	}

	/**
	 * Gene implementation that maps the high-pass and low-pass frequency values from the
	 * source chromosome into a composed {@link AudioPassFilter} processing chain.
	 */
	class FixedFilterGene implements Gene<PackedCollection> {
		/** The gene index within the source chromosome corresponding to this filter gene. */
		private final int index;

		/**
		 * Creates a {@code FixedFilterGene} for the specified source chromosome index.
		 *
		 * @param index the position within the source chromosome
		 */
		public FixedFilterGene(int index) {
			this.index = index;
		}

		@Override
		public int length() { return 1; }

		@Override
		public Factor<PackedCollection> valueAt(int pos) {
			Producer<PackedCollection> lowFrequency = multiply(c(maxFrequency), source.valueAt(index, 0).getResultant(c(1.0)));
			Producer<PackedCollection> highFrequency = multiply(c(maxFrequency), source.valueAt(index, 1).getResultant(c(1.0)));
			Producer<PackedCollection> resonance = scalar(defaultResonance);
			return new AudioPassFilter(sampleRate, lowFrequency, resonance, true)
					.andThen(new AudioPassFilter(sampleRate, highFrequency, resonance, false));
		}
	}

	/**
	 * Restricts the high-pass cutoff frequency gene to the specified normalized range.
	 * The {@code min} and {@code max} values are divided by {@code maxFrequency} before
	 * being applied to each gene's transform.
	 *
	 * @param min the minimum high-pass cutoff frequency in Hz
	 * @param max the maximum high-pass cutoff frequency in Hz
	 */
	public void setHighPassRange(double min, double max) {
		source.forEach(gene -> {
			((ProjectedGene) gene).setRange(0, min / maxFrequency, max / maxFrequency);
		});
	}

	/**
	 * Restricts the low-pass cutoff frequency gene to the specified normalized range.
	 * The {@code min} and {@code max} values are divided by {@code maxFrequency} before
	 * being applied to each gene's transform.
	 *
	 * @param min the minimum low-pass cutoff frequency in Hz
	 * @param max the maximum low-pass cutoff frequency in Hz
	 */
	public void setLowPassRange(double min, double max) {
		source.forEach(gene -> {
			((ProjectedGene) gene).setRange(1, min / maxFrequency, max / maxFrequency);
		});
	}
}
