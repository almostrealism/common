/*
 * Copyright 2016 Michael Murray
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
import java.util.List;

public class ArrayListChromosome<T> extends ArrayList<Gene<T>> implements Chromosome<T>, Breedable {
	public Gene<T> getGene(int index) { return get(index); }
	public int length() { return size(); }
	
	public Breedable breed(Breedable b, List<Breeder> l) {
		if (b instanceof Chromosome<?> == false)
			throw new IllegalArgumentException("Invalid type for breeding");
		
		Chromosome<T> c = (Chromosome<T>) b;
		return l.get(0).combine(this, c);
	}
}
