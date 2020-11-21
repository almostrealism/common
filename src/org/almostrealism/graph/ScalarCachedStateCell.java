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
import org.almostrealism.util.Producer;
import org.almostrealism.util.Provider;

import java.util.function.Supplier;

public class ScalarCachedStateCell extends CachedStateCell<Scalar> {
	public ScalarCachedStateCell() {
		super(Scalar.blank());
	}

	@Override
	protected Supplier<Runnable> assign(Supplier<Producer<? extends Scalar>> out, Supplier<Producer<? extends Scalar>> in) {
		return () -> () -> out.get().evaluate().setValue(in.get().evaluate().getValue());
	}

	@Override
	public Supplier<Runnable> reset(Supplier<Producer<? extends Scalar>> out) {
		return () -> () -> out.get().evaluate().setMem(new double[] { 0.0, 1.0 });
	}
}
