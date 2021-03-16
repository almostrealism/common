/*
 * Copyright 2021 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.algebra.computations;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;

public class PairBankFromPairsBuilder implements Producer<PairBank>, Factory<PairBankFromPairs> {
	private Producer<Pair> producers[];

	public PairBankFromPairsBuilder(int count) {
		producers = new Producer[count];
	}

	public Producer<Pair> get(int index) {
		return producers[index];
	}

	public void set(int index, Producer<Pair> value) {
		producers[index] = value;
	}

	public int getCount() { return producers.length; }

	@Override
	public PairBankFromPairs construct() { return new PairBankFromPairs(producers); }

	@Override
	public Evaluable<PairBank> get() { return construct().get(); }
}
