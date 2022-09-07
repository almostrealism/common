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

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class CombinedGenome implements Genome<PackedCollection<?>>, CollectionFeatures {
	private List<ConfigurableGenome> genomes;

	public CombinedGenome(int genomeCount) {
		this(IntStream.range(0, genomeCount).mapToObj(i -> new ConfigurableGenome()).toArray(ConfigurableGenome[]::new));
	}

	public CombinedGenome(ConfigurableGenome... genomes) {
		this.genomes = List.of(genomes);
	}

	public ConfigurableGenome getGenome(int index) {
		return genomes.get(index);
	}

	public ParameterGenome getParameters() {
		return new ParameterGenome(getAllChromosomes());
	}

	protected List<ConfigurableChromosome> getAllChromosomes() {
		List<ConfigurableChromosome> chromosomes = new ArrayList<>();
		for (ConfigurableGenome genome : genomes) {
			chromosomes.addAll(genome.getChromosomes());
		}
		return chromosomes;
	}

	public void assignTo(Genome<PackedCollection<?>> parameters) {
		List<ConfigurableChromosome> chromosomes = getAllChromosomes();

		for (int x = 0; x < chromosomes.size(); x++) {
			for (int y = 0; y < chromosomes.get(x).length(); y++) {
				PackedCollection<?> params = chromosomes.get(x).getParameters(y);

				for (int z = 0; z < params.getMemLength(); z++) {
					params.setMem(z, parameters.valueAt(x, y, z).getResultant(c(1.0)).get().evaluate().toDouble(0));
				}
			}
		}
	}

	@Override
	public Chromosome<PackedCollection<?>> valueAt(int pos) {
		return getAllChromosomes().get(pos);
	}

	@Override
	public Genome getHeadSubset() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Chromosome getLastChromosome() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int count() { return getAllChromosomes().size(); }
}
