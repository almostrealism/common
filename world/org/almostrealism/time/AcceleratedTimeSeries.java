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

package org.almostrealism.time;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.util.AcceleratedAssignment;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Producer;

import java.util.function.Supplier;

public class AcceleratedTimeSeries extends TemporalScalarBank implements CodeFeatures, HardwareFeatures {

	public AcceleratedTimeSeries(int maxEntries) {
		super(maxEntries, CacheLevel.NONE);
		setBeginCursorIndex(1);
		setEndCursorIndex(1);
	}

	protected int getBeginCursorIndex() { return (int) get(0).getA(); }
	protected void setBeginCursorIndex(int index) { get(0).setA(index); }
	protected int getEndCursorIndex() { return (int) get(0).getB(); }
	protected void setEndCursorIndex(int index) { get(0).setB(index); }

	public int getLength() { return getEndCursorIndex() - getBeginCursorIndex(); }
	public boolean isEmpty() { return getLength() == 0; }

	public void add(TemporalScalar value) {
		if (getEndCursorIndex() >= (getCount() - 1)) {
			throw new RuntimeException("AcceleratedTimeSeries is full");
		}

		setEndCursorIndex(getEndCursorIndex() + 1);
		set(getEndCursorIndex(), value);
	}

	@Deprecated
	public void add(double time, double value) {
		if (getEndCursorIndex() >= (getCount() - 1)) {
			throw new RuntimeException("AcceleratedTimeSeries is full");
		}

		setEndCursorIndex(getEndCursorIndex() + 1);
		set(getEndCursorIndex(), time, value);
	}

	public Runnable add(Supplier<Producer<TemporalScalar>> value) {
		return compileRunnable(new AcceleratedAssignment<>(2, head(), value));
	}

	protected Supplier<Producer<TemporalScalar>> head() {
		return () -> new DynamicProducer<>(args -> {
			setEndCursorIndex(getEndCursorIndex() + 1);
			return get(getEndCursorIndex());
		});
	}

	public Runnable purge(Producer<CursorPair> time) {
		AcceleratedOperation op = new AcceleratedOperation<MemWrapper>("prg", false, p(this), () -> time);
		op.setSourceClass(AcceleratedTimeSeries.class);
		return op;
	}

	public Producer<Scalar> valueAt(Producer<CursorPair> cursor) {
		AcceleratedProducer op = new AcceleratedProducer<MemWrapper, TemporalScalar>("vat", () -> TemporalScalar.blank(), p(this), () -> cursor);
		op.setSourceClass(AcceleratedTimeSeries.class);
		return op;
	}

	public TemporalScalar valueAt(double time) {
		TemporalScalar left = null;
		TemporalScalar right = null;

		i: for (int i = getBeginCursorIndex(); i < getEndCursorIndex(); i++) {
			TemporalScalar v = get(i);
			if (v.getTime() >= time) {
				left = i > getBeginCursorIndex() ? get(i - 1) : null;
				right = get(i);
				break i;
			}
		}

		if (left == null || right == null) return null;
		if (left.getTime() > time) return null;

		double v1 = left.getValue();
		double v2 = right.getValue();

		double t1 = time - left.getTime();
		double t2 = right.getTime() - left.getTime();

		TemporalScalar s = new TemporalScalar(time, v1 + (t1 / t2) * (v2 - v1));
		return s;
	}
}
