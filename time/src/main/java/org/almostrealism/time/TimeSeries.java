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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class TimeSeries {
	private TreeSet<TemporalScalar> sorted;
	private Map<Double, TemporalScalar> byTime;

	public TimeSeries() {
		this.sorted = new TreeSet<>(Comparator.comparing(TemporalScalar::getTime));
		this.byTime = new HashMap<>();
	}

	public synchronized void add(TemporalScalar value) {
		if (byTime.containsKey(value.getTime())) {
			TemporalScalar s = byTime.get(value.getTime());
			s.setValue(s.getValue() + value.getValue());
			return;
		}

		sorted.add(value);
		byTime.put(value.getTime(), value);
	}

	public synchronized void purge(double time) {
		int toRemove = -1;

		s: for (TemporalScalar s : sorted) {
			if (s.getTime() >= time) {
				break s;
			}

			toRemove++;
		}

		Iterator<TemporalScalar> itr = sorted.iterator();

		for (int i = 0; i < toRemove; i++) {
			byTime.remove(itr.next().getTime());
			itr.remove();
		}
	}

	public synchronized TemporalScalar valueAt(double time) {
		List<TemporalScalar> list = new ArrayList<>();
		list.addAll(sorted);

		TemporalScalar left = null, right = null;

		i: for (int i = 0; i < list.size(); i++) {
			if (list.get(i).getTime() >= time) {
				left = i > 0 ? list.get(i - 1) : null;
				right = list.get(i);
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
