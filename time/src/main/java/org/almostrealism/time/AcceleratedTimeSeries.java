/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.MemoryBankAdapter;
import org.almostrealism.time.computations.AcceleratedTimeSeriesAdd;
import org.almostrealism.time.computations.AcceleratedTimeSeriesPurge;
import org.almostrealism.time.computations.AcceleratedTimeSeriesValueAt;

import java.util.function.Supplier;

public class AcceleratedTimeSeries extends MemoryBankAdapter<TemporalScalar> implements Lifecycle {
	public static final int defaultSize = 10 * 1024 * 1024; // 16 * 1024 * 1024;

	public static CacheLevel defaultCacheLevel = CacheLevel.NONE;

	static {
		if (defaultCacheLevel == CacheLevel.ALL) {
			System.out.println("WARN: AcceleratedTimeSeries default cache level is ALL");
		}
	}

	public AcceleratedTimeSeries(int maxEntries) {
		super(2, maxEntries + 1,
				delegateSpec ->
					new TemporalScalar(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				defaultCacheLevel);
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
		if (getEndCursorIndex() >= (getCountLong() - 1)) {
			throw new RuntimeException("AcceleratedTimeSeries is full");
		}

		set(getEndCursorIndex(), value);
		setEndCursorIndex(getEndCursorIndex() + 1);
	}

	@Deprecated
	public void add(double time, double value) {
		if (getEndCursorIndex() >= getCountLong() - 1) {
			throw new RuntimeException("AcceleratedTimeSeries is full");
		}

		setEndCursorIndex(getEndCursorIndex() + 1);
		set(getEndCursorIndex(), time, value);
	}

	public Supplier<Runnable> add(Producer<TemporalScalar> value) {
		return new AcceleratedTimeSeriesAdd(() -> new Provider<>(this), value);
	}

	public Supplier<Runnable> purge(Producer<CursorPair> time) { return purge(time, 1.0); }

	public Supplier<Runnable> purge(Producer<CursorPair> time, double frequency) {
		return new AcceleratedTimeSeriesPurge(() -> new Provider<>(this), time, frequency);
	}

	public Producer<PackedCollection<?>> valueAt(Producer<CursorPair> cursor) {
		return new AcceleratedTimeSeriesValueAt(() -> new Provider<>(this), cursor);
	}

	public TemporalScalar valueAt(double time) {
		TemporalScalar left = null;
		TemporalScalar right = null;

		i: for (int i = getBeginCursorIndex(); i < getEndCursorIndex(); i++) {
			TemporalScalar v = get(i);
			if (v.getTime() >= time) {
				left = i > getBeginCursorIndex() ? get(i - 1) : (v.getTime() == time ? get(i) : null);
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

		if (t2 == 0) {
			return new TemporalScalar(time, v1);
		} else {
			return new TemporalScalar(time, v1 + (t1 / t2) * (v2 - v1));
		}
	}

	@Override
	public void reset() {
		Lifecycle.super.reset();
		setBeginCursorIndex(1);
		setEndCursorIndex(1);
	}

	public static AcceleratedTimeSeries defaultSeries() {
		return new AcceleratedTimeSeries(defaultSize);
	}
}
