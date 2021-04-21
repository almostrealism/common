/*
 * Copyright 2021 Michael Murray
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

import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class FloatingPointRandomChromosomeFactory implements ChromosomeFactory<Scalar> {
	private int genes, factors;
	
	@Override
	public FloatingPointRandomChromosomeFactory setChromosomeSize(int genes, int factors) {
		this.genes = genes;
		this.factors = factors;
		return this;
	}
	
	@Override
	public Chromosome<Scalar> generateChromosome(double arg) {
		return IntStream.range(0, genes)
				.mapToObj(i -> IntStream.range(0, factors)
						.mapToObj(j -> new ScaleFactor(StrictMath.random() * arg))
						.collect(Collectors.toCollection(ArrayListGene::new)))
				.collect(Collectors.toCollection(ArrayListChromosome::new));
	}
}
