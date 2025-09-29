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

import io.almostrealism.lifecycle.Destroyable;
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

public class TimeCell implements Cell<Scalar>, Temporal, Destroyable, CodeFeatures {
	private Receptor r;
	private Pair<?> time;
	private Producer<PackedCollection<?>> initial, loopDuration;
	private PackedCollection<?> resets;

	public TimeCell() {
		this(1);
	}

	public TimeCell(int maxResets) {
		this.time = new Pair<>();
		this.resets = new PackedCollection<>(maxResets);
		initResets();
	}

	public TimeCell(Producer<PackedCollection<?>> initial, Producer<PackedCollection<?>> loopDuration) {
		this(initial, loopDuration, 1);
	}

	public TimeCell(Producer<PackedCollection<?>> initial, Producer<PackedCollection<?>> loopDuration, int maxResets) {
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

	public int getReset(int index) {
		return (int) resets.toDouble(index);
	}

	@Override
	public Supplier<Runnable> setup() {
		if (initial == null) {
			return a(cp(time), c(0.0).repeat(2));
		} else {
			return a(cp(time), repeat(2, initial));
		}
	}

	@Override
	public Supplier<Runnable> push(Producer<Scalar> protein) {
		return r == null ? new OperationList("TimeCell Push") : r.push(frameScalar());
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("TimeCell Tick");

		if (loopDuration == null) {
			tick.add(a(cp(time),
					add(cp(time), c(1.0).repeat(2))));
		} else {
			Producer<PackedCollection<?>> ld = c(loopDuration, 0);
			Producer<PackedCollection<?>> left = cp(time.range(shape(1)));
			left = add(left, c(1.0));
			left = greaterThanConditional(ld, c(0.0), mod(left, ld), left, false);

			Producer<PackedCollection<?>> right = cp(time.range(shape(1), 1));
			right = add(right, c(1.0));

			tick.add(a(cp(time.range(shape(1))), left));
			tick.add(a(cp(time.range(shape(1), 1)), right));
		}

		tick.add(new TimeCellReset(p(time), resets));
		return tick;
	}

	@Override
	public void setReceptor(Receptor<Scalar> r) {
		if (cellWarnings && this.r != null) {
			warn("Replacing receptor");
		}

		this.r = r;
	}

	public void setFrame(double frame) {
		double f = Math.floor(frame);
		time.setMem(f, f);
	}

	public double getFrame() {
		return time.toDouble(0);
	}

	public Producer<Scalar> frameScalar() { return l(cp(time)); }

	public Producer<PackedCollection<?>> frame() { return cp(time.range(shape(1))); }

	public Producer<PackedCollection<?>> time(double sampleRate) {
		return divide(frame(), c(sampleRate));
	}

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		if (time != null) time.destroy();
		if (resets != null) resets.destroy();
		time = null;
		resets = null;
	}
}
