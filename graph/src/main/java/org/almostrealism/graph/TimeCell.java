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
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.computations.TimeCellReset;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;
import java.util.stream.IntStream;

public class TimeCell implements Cell<Scalar>, Temporal, CodeFeatures {
	private Receptor r;
	private Pair<?> time;
	private Producer<Scalar> initial, loopDuration;
	private PackedCollection<?> resets;

	public TimeCell() {
		time = new Pair<>();
	}

	public TimeCell(int maxResets) {
		this();
		this.resets = new PackedCollection<>(maxResets);
		initResets();
	}

	public TimeCell(Producer<Scalar> initial, Producer<Scalar> loopDuration) {
		this(initial, loopDuration, 1);
	}

	public TimeCell(Producer<Scalar> initial, Producer<Scalar> loopDuration, int maxResets) {
		this(maxResets);
		this.initial = initial;
		this.loopDuration = loopDuration;
	}

	protected void initResets() {
		double initial[] = new double[resets.getMemLength()];
		IntStream.range(0, initial.length).forEach(i -> initial[i] = -1);
		resets.setMem(initial);
	}

	public void setReset(int index, int value) {
		resets.setMem(index, (double) value);
	}

	@Override
	public Supplier<Runnable> setup() {
		if (initial == null) {
			return new Assignment<>(2, () -> new Provider<>(time), PairFeatures.of(0.0, 0.0));
		} else {
			return new Assignment<>(2, () -> new Provider<>(time), pair(initial, initial));
		}
	}

	@Override
	public Supplier<Runnable> push(Producer<Scalar> protein) {
		return r == null ? new OperationList("TimeCell Push") : r.push(frame());
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("TimeCell Tick");

		if (loopDuration == null) {
			tick.add(new Assignment<>(2, () -> new Provider<>(time),
					pairAdd(() -> new Provider<>(time),
							PairFeatures.of(1.0, 1.0))));
		} else {
			Producer<Scalar> left = l(() -> new Provider<>(time));
			left = greaterThan(loopDuration, v(0.0),
					mod(scalarAdd(left, ScalarFeatures.of(new Scalar(1.0))), loopDuration),
					scalarAdd(left, ScalarFeatures.of(new Scalar(1.0))), false);

			Producer<Scalar> right = r(() -> new Provider<>(time));
			right = scalarAdd(right, ScalarFeatures.of(1.0));

			tick.add(new Assignment<>(2, () -> new Provider<>(time), pair(left, right)));
		}

		tick.add(new TimeCellReset(() -> new Provider<>(time), resets));
		return tick;
	}

	@Override
	public void setReceptor(Receptor<Scalar> r) { this.r = r; }

	public Producer<Scalar> frame() { return l(() -> new Provider<>(time)); }
}
