/*
 * Copyright 2022 Michael Murray
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
import org.almostrealism.collect.PackedCollection;

import java.io.IOException;
import java.util.List;

public class ParameterGenome implements Genome<PackedCollection<?>> {
	private AssignableGenome genome;

	public ParameterGenome() { }

	protected ParameterGenome(List<ConfigurableChromosome> chromosomes) {
		genome = new AssignableGenome();

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

	private ParameterGenome(AssignableGenome genome) {
		this.genome = genome;
	}

	@Override
	public Genome getHeadSubset() {
		return genome.getHeadSubset();
	}

	@Override
	public Chromosome getLastChromosome() {
		return genome.getLastChromosome();
	}

	@Override
	public int count() {
		return genome.count();
	}

	@Override
	public Chromosome<PackedCollection<?>> valueAt(int pos) {
		return genome.valueAt(pos);
	}

	public ParameterGenome random() {
		AssignableGenome random = new AssignableGenome();

		for (int x = 0; x < genome.length(); x++) {
			for (int y = 0; y < genome.length(x); y++) {
				for (int z = 0; z < genome.length(x, y); z++) {
					random.insert(new Scalar(Math.random()), x, y, z);
				}
			}
		}

		return new ParameterGenome(random);
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
