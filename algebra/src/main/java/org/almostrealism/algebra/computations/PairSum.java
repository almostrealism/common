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

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.PairProducer;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class PairSum extends NAryDynamicProducer<Pair<?>> implements PairProducer {
	@SafeVarargs
	public PairSum(Supplier<Evaluable<? extends Pair<?>>>... producers) {
		super("+", 2, Pair.empty(), PairBank::new, producers);
	}

	@Override
	public double getIdentity() { return 0; }

	@Override
	public double combine(double a, double b) { return a + b; }

	/**
	 * Returns true if the specified value is 0.0, false otherwise.
	 */
	@Override
	public boolean isRemove(double value) { return value == 0.0; }
}
