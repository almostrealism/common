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

package org.almostrealism.audio.optimize;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.ProjectedGene;

public class FixedFilterChromosome implements Chromosome<PackedCollection>, CellFeatures {
	public static final int SIZE = 2;

	public static double defaultResonance = 0.2;
	private static final double maxFrequency = 20000;

	private final Chromosome<PackedCollection> source;
	private final int sampleRate;

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

	class FixedFilterGene implements Gene<PackedCollection> {
		private final int index;

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

	public void setHighPassRange(double min, double max) {
		source.forEach(gene -> {
			((ProjectedGene) gene).setRange(0, min / maxFrequency, max / maxFrequency);
		});
	}

	public void setLowPassRange(double min, double max) {
		source.forEach(gene -> {
			((ProjectedGene) gene).setRange(1, min / maxFrequency, max / maxFrequency);
		});
	}
}
