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

import io.almostrealism.uml.Plural;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

public interface Genome<T> extends Plural<Chromosome<T>> {
	Genome getHeadSubset();
	
	Chromosome getLastChromosome();

	int count();

	default String signature() {
		StringBuffer buf = new StringBuffer();
		IntStream.range(0, count()).mapToObj(this::valueAt).map(Chromosome::signature).forEach(buf::append);
		return buf.toString();
	}

	default Gene<T> valueAt(int chromosome, int gene) {
		return valueAt(chromosome).valueAt(gene);
	}

	default Factor<T> valueAt(int chromosome, int gene, int factor) {
		return valueAt(chromosome).valueAt(gene, factor);
	}

	static <T> GenomeFromChromosomes<T> fromChromosomes(ChromosomeFactory<T>... factories) {
		return new GenomeFromChromosomes(factories);
	}
}
