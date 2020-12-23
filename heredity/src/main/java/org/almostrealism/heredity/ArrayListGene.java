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

import java.util.ArrayList;

public class ArrayListGene<T> extends ArrayList<Factor<T>> implements Gene<T> {
	public ArrayListGene() { }

	public ArrayListGene(double... f) {
		for (double d : f) {
			add((Factor<T>) new DoubleScaleFactor(d));
		}
	}

	public ArrayListGene(Factor<T>... factors) {
		for (Factor<T> f : factors) add(f);
	}

	@Override
	public Factor<T> getFactor(int index) { return get(index); }

	@Override
	public int length() { return size(); }
}
