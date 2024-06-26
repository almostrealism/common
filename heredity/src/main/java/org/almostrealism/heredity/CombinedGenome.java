/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Factor;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CombinedGenome implements Genome<PackedCollection<?>>, CollectionFeatures {
	private List<ConfigurableGenome> genomes;

	public CombinedGenome(int genomeCount) {
		this(IntStream.range(0, genomeCount).mapToObj(i -> new ConfigurableGenome()).toArray(ConfigurableGenome[]::new));
	}

	protected CombinedGenome(ConfigurableGenome... genomes) {
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

	public DefaultGenomeBreeder<PackedCollection<?>> getBreeder() {
		return new DefaultGenomeBreeder<>(getAllChromosomes().stream().map(ConfigurableChromosome::getBreeder).collect(Collectors.toList()));
	}

	public void assignTo(Genome<PackedCollection<?>> parameters) {
		List<ConfigurableChromosome> chromosomes = getAllChromosomes();

		for (int x = 0; x < chromosomes.size(); x++) {
			for (int y = 0; y < chromosomes.get(x).length(); y++) {
				PackedCollection<?> params = chromosomes.get(x).getParameters(y);

				for (int z = 0; z < params.getMemLength(); z++) {
					params.setMem(z, value(parameters.valueAt(x, y, z)));
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

	private double value(Factor<PackedCollection<?>> factor) {
		if (factor instanceof ScaleFactor) {
			return ((ScaleFactor) factor).getScaleValue();
		} else if (factor instanceof AssignableGenome.AssignableFactor) {
			PackedCollection<?> value = ((AssignableGenome.AssignableFactor) factor).getValue();
			if (value == null) {
				throw new UnsupportedOperationException();
			}

			return value.toDouble(0);
		} else {
			return factor.getResultant(c(1.0)).get().evaluate().toDouble(0);
		}
	}
}
