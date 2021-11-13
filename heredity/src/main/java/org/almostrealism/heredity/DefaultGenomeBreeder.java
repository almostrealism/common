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

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class DefaultGenomeBreeder<T> implements GenomeBreeder<T> {
	private static PrintWriter log;

	private final List<ChromosomeBreeder<T>> breeders;

	public DefaultGenomeBreeder(ChromosomeBreeder<T>... breeders) {
		this(Arrays.asList(breeders));
	}

	public DefaultGenomeBreeder(List<ChromosomeBreeder<T>> breeders) {
		this.breeders = breeders;
	}

	@Override
	public Genome combine(Genome<T> g1, Genome<T> g2) {
		ArrayListGenome result = new ArrayListGenome();
		IntStream.range(0, breeders.size()).forEach(i ->
			result.add(breeders.get(i).combine(g1.valueAt(i), g2.valueAt(i))));

		if (log != null) log.println(g1 + " + " + g2 + " = " + result);
		return result;
	}
}
