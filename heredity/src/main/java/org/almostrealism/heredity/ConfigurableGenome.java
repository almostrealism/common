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
import java.util.function.IntFunction;
import java.util.stream.Collectors;

public class ConfigurableGenome implements Genome<PackedCollection<?>>, CollectionFeatures {
	private List<ConfigurableChromosome> chromosomes;
	private IntFunction<PackedCollection<?>> supply;

	public ConfigurableGenome() {
		this(PackedCollection::new);
	}

	public ConfigurableGenome(IntFunction<PackedCollection<?>> supply) {
		this.chromosomes = new ArrayList<>();
		this.supply = supply;
	}

	public ParameterGenome getParameters() {
		return new ParameterGenome(chromosomes);
	}

	protected List<ConfigurableChromosome> getChromosomes() {
		return chromosomes;
	}

	public DefaultGenomeBreeder<PackedCollection<?>> getBreeder() {
		return new DefaultGenomeBreeder<>(chromosomes.stream().map(ConfigurableChromosome::getBreeder).collect(Collectors.toList()));
	}

	public void assignTo(Genome<PackedCollection<?>> parameters) {
		for (int x = 0; x < chromosomes.size(); x++) {
			for (int y = 0; y < chromosomes.get(x).length(); y++) {
				PackedCollection<?> params = chromosomes.get(x).getParameters(y);

				for (int z = 0; z < params.getMemLength(); z++) {
					params.setMem(z, parameters.valueAt(x, y, z).getResultant(c(1.0)).get().evaluate().toDouble(0));
				}
			}
		}
	}

	public SimpleChromosome addSimpleChromosome(int geneLength) {
		SimpleChromosome chromosome = new SimpleChromosome(geneLength, supply);
		chromosomes.add(chromosome);
		return chromosome;
	}

	public void removeChromosome(int index) {
		chromosomes.remove(index);
	}

	@Override
	public Chromosome<PackedCollection<?>> valueAt(int pos) {
		return chromosomes.get(pos);
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
	public int count() { return chromosomes.size(); }
}
