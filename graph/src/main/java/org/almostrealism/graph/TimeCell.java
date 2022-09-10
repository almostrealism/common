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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

public class TimeCell implements Cell<Scalar>, Temporal, CodeFeatures {
	private Receptor r;
	private Scalar time;
	private Producer<Scalar> initial, loopDuration;

	public TimeCell() {
		time = new Scalar();
	}

	public TimeCell(Producer<Scalar> initial, Producer<Scalar> loopDuration) {
		this();
		this.initial = initial;
		this.loopDuration = loopDuration;
	}

	@Override
	public Supplier<Runnable> setup() {
		if (initial == null) {
			return new Assignment<>(1, () -> new Provider<>(time), ScalarFeatures.of(new Scalar(0.0)));
		} else {
			return new Assignment<>(1, () -> new Provider<>(time), initial);
		}
	}

	@Override
	public Supplier<Runnable> push(Producer<Scalar> protein) {
		return r == null ? new OperationList("TimeCell Push") : r.push(this::getFrame);
	}

	@Override
	public Supplier<Runnable> tick() {
		if (loopDuration == null) {
			return new Assignment<>(1, () -> new Provider<>(time),
					scalarAdd(() -> new Provider<>(time),
							ScalarFeatures.of(new Scalar(1.0))));
		} else {
			return new Assignment<>(1, () -> new Provider<>(time),
					greaterThan(loopDuration, v(0.0),
						mod(scalarAdd(() -> new Provider<>(time),
							ScalarFeatures.of(new Scalar(1.0))), loopDuration),
						scalarAdd(() -> new Provider<>(time),
							ScalarFeatures.of(new Scalar(1.0))), false));
		}
	}

	@Override
	public void setReceptor(Receptor<Scalar> r) { this.r = r; }

	public Provider<Scalar> getFrame() { return new Provider<>(time); }
}
