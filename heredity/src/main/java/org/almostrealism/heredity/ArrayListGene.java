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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

public class ArrayListGene<T> extends ArrayList<Factor<T>> implements Gene<T> {
	public ArrayListGene() { }

	public ArrayListGene(double... f) {
		for (double d : f) {
			add((Factor<T>) new ScaleFactor(d));
		}
	}

	@SafeVarargs
	public ArrayListGene(Factor<T>... factors) {
		this.addAll(Arrays.asList(factors));
	}

	@Override
	public boolean addAll(Collection<? extends Factor<T>> factors) {
		if (factors.stream().filter(Objects::nonNull).count() != factors.size()) {
			throw new IllegalArgumentException();
		}

		return super.addAll(factors);
	}

	@Override
	public boolean add(Factor<T> factor) {
		if (factor == null) {
			throw new IllegalArgumentException();
		}

		return super.add(factor);
	}

	@Override
	public Factor<T> valueAt(int index) { return get(index); }

	@Override
	public int length() { return size(); }
}
