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

import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

public class ProjectedGenome implements Genome<PackedCollection<?>> {
	private PackedCollection<?> parameters;
	private List<ProjectedChromosome> chromosomes;

	public ProjectedGenome(int parameters) {
		this(new PackedCollection(parameters));
	}

	public ProjectedGenome(PackedCollection<?> parameters) {
		this.parameters = parameters;
		this.chromosomes = new ArrayList<>();
	}

	public void initWeights(Supplier<Random> seeds) {
		for (ProjectedChromosome chromosome : chromosomes) {
			chromosome.initWeights(seeds.get()::nextLong);
		}
	}

	public void refreshValues() {
		for (ProjectedChromosome chromosome : chromosomes) {
			chromosome.refreshValues();
		}
	}

	public ProjectedChromosome addChromosome() {
		ProjectedChromosome chromosome = new ProjectedChromosome(parameters);
		chromosomes.add(chromosome);
		return chromosome;
	}

	public void removeChromosome(int index) {
		chromosomes.remove(index);
	}

	public PackedCollection<?> getParameters() { return parameters; }

	public void assignTo(PackedCollection<?> parameters) {
		if (parameters.getShape().getTotalSize() != this.parameters.getShape().getTotalSize())
			throw new IllegalArgumentException();

		this.parameters.setMem(0, parameters);
	}

	@Override
	public Chromosome<PackedCollection<?>> valueAt(int pos) {
		return chromosomes.get(pos);
	}

	@Override
	public int count() {
		return chromosomes.size();
	}

	public ProjectedGenome variation(double min, double max, double rate, DoubleSupplier delta) {
		PackedCollection<?> variation = new PackedCollection<>(parameters.getShape());
		variation.fill(pos -> {
			double v = parameters.valueAt(pos);

			if (Math.random() < rate) {
				return Math.min(max, Math.max(min, v + delta.getAsDouble()));
			} else {
				return v;
			}
		});

		return new ProjectedGenome(variation);
	}

	public ProjectedGenome random() {
		return new ProjectedGenome(new PackedCollection<>(parameters.getShape()).randFill());
	}
}
