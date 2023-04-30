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

import java.util.List;

public class SimpleChromosome extends ConfigurableChromosome {
	private int geneLength;
	private ArrayListChromosome<PackedCollection<?>> chromosome;

	public SimpleChromosome(int geneLength) {
		this.geneLength = geneLength;
		this.chromosome = new ArrayListChromosome<>();
	}

	public SimpleGene addGene() {
		SimpleGene gene = new SimpleGene(geneLength);
		chromosome.add(gene);
		return gene;
	}

	public ChoiceGene addChoiceGene(PackedCollection<?> choices) {
		ChoiceGene gene = new ChoiceGene(choices, geneLength);
		chromosome.add(gene);
		return gene;
	}

	public void removeGene(int index) {
		chromosome.remove(index);
	}

	@Override
	public PackedCollection<?> getParameters(int gene) {
		return ((GeneParameters) chromosome.valueAt(gene)).getParameters();
	}

	@Override
	public PackedCollection<?> getParameterRanges(int gene) {
		return ((GeneParameters) chromosome.valueAt(gene)).getParameterRanges();
	}

	public void setParameterRange(int factor, double min, double max) {
		for (int i = 0; i < chromosome.size(); i++) {
			((SimpleGene) chromosome.valueAt(i)).setRange(factor, min, max);
		}
	}

	@Override
	public ChromosomeBreeder<PackedCollection<?>> getBreeder() {
		// return Breeders.averageBreeder();
		// return Breeders.randomChoiceBreeder();
		return Breeders.perturbationBreeder(0.01, ScaleFactor::new);
	}

	@Override
	public Gene<PackedCollection<?>> valueAt(int pos) {
		return chromosome.valueAt(pos);
	}

	@Override
	public int length() {
		return chromosome.length();
	}
}
