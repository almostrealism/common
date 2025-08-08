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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.PackedCollection;

import java.io.IOException;
import java.util.List;
import java.util.function.DoubleSupplier;

public class ParameterGenome implements Genome<PackedCollection<?>>, ScalarFeatures {
	private List<ConfigurableChromosome> chromosomes;
	private AssignableGenome genome;

	public ParameterGenome() {
	}

	protected ParameterGenome(List<ConfigurableChromosome> chromosomes) {
		this.chromosomes = chromosomes;
		this.genome = new AssignableGenome();

		for (int x = 0; x < chromosomes.size(); x++) {
			for (int y = 0; y < chromosomes.get(x).length(); y++) {
				PackedCollection<?> parameters = chromosomes.get(x).getParameters(y);
				double p[] = parameters.toArray(0, parameters.getMemLength());

				for (int z = 0; z < p.length; z++) {
					genome.insert(new Scalar(p[z]), x, y, z);
				}
			}
		}
	}

	private ParameterGenome(List<ConfigurableChromosome> chromosomes, AssignableGenome genome) {
		this.chromosomes = chromosomes;
		this.genome = genome;
	}

	@Override
	public int count() {
		return genome.count();
	}

	public int getTotalSize() { return genome.getTotalSize(); }

	@Override
	public Chromosome<PackedCollection<?>> valueAt(int pos) {
		return genome.valueAt(pos);
	}

	public ParameterGenome random() {
		if (chromosomes != null) return random(chromosomes);

		System.out.println("WARN: No chromosomes to use for determining range of random values");

		AssignableGenome random = new AssignableGenome();

		for (int x = 0; x < genome.length(); x++) {
			for (int y = 0; y < genome.length(x); y++) {
				for (int z = 0; z < genome.length(x, y); z++) {
					random.insert(new Scalar(Math.random()), x, y, z);
				}
			}
		}

		return new ParameterGenome(null, random);
	}

	public ParameterGenome variation(double min, double max, double rate, DoubleSupplier delta) {
		AssignableGenome random = new AssignableGenome();

		for (int x = 0; x < genome.length(); x++) {
			for (int y = 0; y < genome.length(x); y++) {
				for (int z = 0; z < genome.length(x, y); z++) {
					double v = genome.get(x, y, z).toDouble(0);

					if (Math.random() < rate) {
						v = Math.min(max, Math.max(min, v + delta.getAsDouble()));
					}

					random.insert(new Scalar(v), x, y, z);
				}
			}
		}

		return new ParameterGenome(chromosomes, random);
	}

	public ParameterGenome random(List<ConfigurableChromosome> chromosomes) {
		AssignableGenome random = new AssignableGenome();

		for (int x = 0; x < genome.length(); x++) {
			for (int y = 0; y < genome.length(x); y++) {
				PackedCollection<?> ranges = chromosomes.get(x).getParameterRanges(y);

				for (int z = 0; z < genome.length(x, y); z++) {
					double min = ranges.get(z).toDouble(0);
					double max = ranges.get(z).toDouble(1);
					double len = max - min;
					random.insert(new Scalar(min + len * Math.random()), x, y, z);
				}
			}
		}

		return new ParameterGenome(chromosomes, random);
	}

	public String getSerialized() throws IOException {
		return genome == null ? null : genome.getSerialized();
	}

	public void setSerialized(String serialized) throws IOException {
		if (serialized == null) {
			genome = null;
			return;
		}

		genome = new AssignableGenome();
		genome.setSerialized(serialized);
	}
}
