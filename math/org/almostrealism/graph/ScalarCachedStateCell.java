/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph;

import org.almostrealism.algebra.Scalar;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class ScalarCachedStateCell extends CachedStateCell<Scalar> {
	private static Scalar zero = new Scalar();

	public ScalarCachedStateCell() {
		super(Scalar.blank().get());
	}

	@Override
	protected Supplier<Runnable> assign(Supplier<Evaluable<? extends Scalar>> out, Supplier<Evaluable<? extends Scalar>> in) {
		// return () -> () -> out.get().evaluate().setValue(in.get().evaluate().getValue());
		return a(2, out, in);
	}

	@Override
	public Supplier<Runnable> reset(Supplier<Evaluable<? extends Scalar>> out) {
		// return () -> () -> out.get().evaluate().setMem(new double[] { 0.0, 1.0 });
		return a(2, out, v(zero));
	}
}
