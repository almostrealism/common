/*
 * Copyright 2022 Michael Murray
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
import org.almostrealism.algebra.PairEvaluable;
import org.almostrealism.hardware.DestinationSupport;

import java.util.function.Supplier;

public class RandomPair implements PairEvaluable, DestinationSupport<Pair<?>> {
	private Supplier<Pair<?>> destination = Pair::new;

	@Override
	public void setDestination(Supplier<Pair<?>> destination) {
		this.destination = destination;
	}

	@Override
	public Supplier<Pair<?>> getDestination() {
		return destination;
	}

	/**
	 * Produce a {@link Pair} with all values randomly selected
	 * between 0 and 1.
	 */
	@Override
	public Pair evaluate(Object... args) {
		Pair r = destination.get();
		r.setMem(new double[] {
				Math.random(), Math.random() });
		return r;
	}
}
