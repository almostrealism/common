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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ArrayListGenome extends ArrayList<Chromosome<?>> implements Genome {
	public ArrayListGenome() { this(new ArrayList<>()); }

	public ArrayListGenome(List<Chromosome<?>> somes) { addAll(somes); }

	@Override
	public Chromosome<?> valueAt(int pos) { return get(pos); }

	@Override
	public Genome getHeadSubset() {
		ArrayListGenome subset = new ArrayListGenome();
		Iterator<Chromosome<?>> itr = iterator();

		while (itr.hasNext()) {
			Chromosome c = itr.next();
			if (itr.hasNext()) subset.add(c);
		}

		return subset;
	}

	@Override
	public Chromosome getLastChromosome() { return get(size() - 1); }

	@Override
	public int count() { return size(); }

	@Override
	public String toString() {
		StringBuffer buf = new StringBuffer();
		stream().map(Chromosome::toString).map(s -> s + "\n").forEach(buf::append);
		return buf.toString();
	}
}
