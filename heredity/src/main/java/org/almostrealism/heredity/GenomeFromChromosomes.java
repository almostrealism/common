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

import java.util.function.Supplier;

public class GenomeFromChromosomes implements Supplier<Genome> {
	private ChromosomeFactory factories[];
	
	public GenomeFromChromosomes(ChromosomeFactory... factories) {
		this.factories = factories;
	}

	@Override
	public Genome get() {
		ArrayListGenome g = new ArrayListGenome();
		for (ChromosomeFactory f : factories) g.add(f.generateChromosome(1.0));
		return g;
	}
}
