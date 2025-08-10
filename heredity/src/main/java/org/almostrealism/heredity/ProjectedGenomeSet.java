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

import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @deprecated This unnecessarily duplicates ProjectedGenome, which could
 *             be used directly in the same way as this type is.
 */
@Deprecated
public class ProjectedGenomeSet {
	public static final long initialSeed = 0xDEAD;

	private PackedCollection<?> parameters;
	private List<ProjectedGenome> genomes;

	public ProjectedGenomeSet(int parameters, int projections) {
		this.parameters = new PackedCollection<>(parameters);
		this.genomes = IntStream.range(0, projections)
				.mapToObj(i -> new ProjectedGenome(this.parameters))
				.collect(Collectors.toList());
	}

	protected ProjectedGenomeSet(PackedCollection<?> parameters) {
		this.parameters = parameters;
	}

	public void initWeights() {
		Random random = new Random(initialSeed);
		for (ProjectedGenome genome : genomes) {
			genome.initWeights(() -> new Random(random.nextLong()));
		}
	}

	public void refreshValues() {
		for (ProjectedGenome genome : genomes) {
			genome.refreshValues();
		}
	}

	public ProjectedGenome getGenome(int index) {
		return genomes.get(index);
	}

	public PackedCollection<?> getParameters() { return parameters; }

	public void assignTo(PackedCollection<?> parameters) {
		if (parameters.getShape().getTotalSize() != this.parameters.getShape().getTotalSize())
			throw new IllegalArgumentException();

		this.parameters.setMem(0, parameters);
		initWeights();
		refreshValues();
	}

	public ProjectedGenome variation(double min, double max, double rate, DoubleSupplier delta) {
		return new ProjectedGenome(parameters).variation(min, max, rate, delta);
	}

	public ProjectedGenome random() {
		return new ProjectedGenome(parameters).random();
	}
}
