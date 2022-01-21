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

package org.almostrealism.graph.temporal;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.graph.Adjustable;
import org.almostrealism.graph.ScalarCachedStateCell;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public abstract class ScalarTemporalCellAdapter extends ScalarCachedStateCell implements Adjustable<Scalar>, CodeFeatures {
	public static final double PI = Math.PI;

	public static double depth = 1.0;

	private final OperationList setup;

	public ScalarTemporalCellAdapter() {
		setup = new OperationList("ScalarTemporalCellAdapter Setup");
	}

	public void addSetup(Supplier<Runnable> setup) {
		this.setup.add(setup);
	}

	@Override
	public Supplier<Runnable> updateAdjustment(Producer<Scalar> value) {
		return new OperationList("ScalarTemporalCellAdapter Adjustment Update");
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("ScalarTemporalCellAdapter Setup");
		setup.add(super.setup());
		setup.add(this.setup);
		return setup;
	}

	public static ScalarTemporalCellAdapter from(Producer<Scalar> p) {
		return new ScalarTemporalCellAdapter() {
			@Override
			public Supplier<Runnable> push(Producer<Scalar> protein) {
				return assign(() -> new Provider<>(getCachedValue()), p);
			}
		};
	}
}
