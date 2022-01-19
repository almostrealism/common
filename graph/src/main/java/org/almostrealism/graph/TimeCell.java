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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarSum;
import org.almostrealism.algebra.computations.StaticScalarComputation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

public class TimeCell implements Cell<Scalar>, Temporal {
	private Receptor r;
	private Scalar time;

	public TimeCell() {
		time = new Scalar();
	}

	@Override
	public Supplier<Runnable> setup() {
		return new Assignment<>(1, () -> new Provider<>(time), new StaticScalarComputation(new Scalar(0.0)));
	}

	@Override
	public Supplier<Runnable> push(Producer<Scalar> protein) {
		return r == null ? new OperationList("TimeCell Push") : r.push(() -> new Provider<>(time));
	}

	@Override
	public Supplier<Runnable> tick() {
		return new Assignment<>(1, () -> new Provider<>(time),
				new ScalarSum(() -> new Provider<>(time),
						new StaticScalarComputation(new Scalar(1.0))));
	}

	@Override
	public void setReceptor(Receptor<Scalar> r) { this.r = r; }
}
