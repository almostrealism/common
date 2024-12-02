/*
 * Copyright 2020 Michael Murray
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
import io.almostrealism.relation.Producer;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class IdentityFactor<T> implements Factor<T> {
	@Override
	public Producer<T> getResultant(Producer<T> value) { return value; }

	public static <T> Chromosome<T> chromosome(int genes, int factors) {
		ArrayListChromosome<T> chrom = new ArrayListChromosome<T>();

		IntStream.range(0, genes).mapToObj(i -> IntStream.range(0, factors)
				.mapToObj(j -> new IdentityFactor<>()).collect(Collectors.toCollection(ArrayListGene::new)))
				.forEach(gene -> chrom.add((Gene<T>) gene));

		return chrom;
	}
}
