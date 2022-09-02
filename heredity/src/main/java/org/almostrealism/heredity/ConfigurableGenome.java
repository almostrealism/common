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

import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;

public class ConfigurableGenome implements Genome<PackedCollection<?>> {
	private List<ConfigurableChromosome> chromosomes;

	public ConfigurableGenome() {
		this.chromosomes = new ArrayList<>();
	}

	public Genome<PackedCollection<?>> getParameters() {
		throw new UnsupportedOperationException();
	}

	public void assignTo(Genome<PackedCollection<?>> parameters) {
//		throw new UnsupportedOperationException();
		System.out.println("WARN: ConfigurableGenome.assignTo() is not implemented");
	}

	public SimpleChromosome addSimpleChromosome(int geneLength) {
		SimpleChromosome chromosome = new SimpleChromosome(geneLength);
		chromosomes.add(chromosome);
		return chromosome;
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
