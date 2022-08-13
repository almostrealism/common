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

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class RandomChromosomeFactory implements ChromosomeFactory<PackedCollection<?>> {
	private int genes, factors;
	private Tensor<Pair> ranges;

	public RandomChromosomeFactory() {
		ranges = new Tensor<>();
	}
	
	@Override
	public RandomChromosomeFactory setChromosomeSize(int genes, int factors) {
		this.genes = genes;
		this.factors = factors;
		return this;
	}

	public void setRange(int gene, int factor, Pair range) {
		ranges.insert(range, gene, factor);
	}
	
	@Override
	public Chromosome<PackedCollection<?>> generateChromosome(double arg) {
		return IntStream.range(0, genes)
				.mapToObj(i -> IntStream.range(0, factors)
						.mapToObj(j -> new ScaleFactor(value(i, j) * arg))
						.collect(Collectors.toCollection(ArrayListGene::new)))
				.collect(Collectors.toCollection(ArrayListChromosome::new));
	}

	protected double value(int gene, int factor) {
		Pair range = ranges.get(gene, factor);
		return range == null ? Math.random() :
				(range.getLeft() + Math.random() * (range.getRight() - range.getLeft()));
	}
}
