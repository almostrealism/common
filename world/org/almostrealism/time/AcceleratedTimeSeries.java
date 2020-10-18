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

public class AcceleratedTimeSeries extends TemporalScalarBank {

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

	public synchronized void add(TemporalScalar value) {
		if (getEndCursorIndex() >= (getCount() - 1)) {
			throw new RuntimeException("AcceleratedTimeSeries is full");
		}

		setEndCursorIndex(getEndCursorIndex() + 1);
		set(getEndCursorIndex(), value);
	}

	public synchronized void purge(double time) {
		if (isEmpty()) return;

		for (int i = getBeginCursorIndex() + 1; i < getEndCursorIndex(); i++) {
			if (get(i).getTime() > time) {
				setBeginCursorIndex(i - 1);
				return;
			}
		}
	}

	public synchronized TemporalScalar valueAt(double time) {
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
