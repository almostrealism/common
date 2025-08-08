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

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public class ProjectedChromosome implements Chromosome<PackedCollection<?>>, CollectionFeatures {
	private final PackedCollection<?> source;

	private List<ProjectedGene> projections;
	private List<Gene<PackedCollection<?>>> genes;

	public ProjectedChromosome(PackedCollection<?> source) {
		this.source = source;
		this.projections = new ArrayList<>();
		this.genes = new ArrayList<>();
	}

	public void initWeights(LongSupplier seeds) {
		for (ProjectedGene gene : projections) {
			gene.initWeights(seeds.getAsLong());
		}
	}

	public void refreshValues() {
		for (ProjectedGene gene : projections) {
			gene.refreshValues();
		}
	}

	protected ProjectedGene createGene(int length) {
		int input = source.getShape().getTotalSize();
		PackedCollection<?> weight = new PackedCollection<>(shape(length, input).traverse(1));
		ProjectedGene gene = new ProjectedGene(source, weight);
		projections.add(gene);
		return gene;
	}

	public ProjectedGene addGene(int length) {
		ProjectedGene gene = createGene(length);
		genes.add(gene);
		return gene;
	}

	public ChoiceGene addChoiceGene(PackedCollection<?> choices, int length) {
		ChoiceGene gene = new ChoiceGene(createGene(length), choices);
		genes.add(gene);
		return gene;
	}

	public void removeGene(int index) {
		genes.remove(index);
	}

	@Override
	public Gene<PackedCollection<?>> valueAt(int pos) {
		return genes.get(pos);
	}

	@Override
	public int length() { return genes.size(); }
}
