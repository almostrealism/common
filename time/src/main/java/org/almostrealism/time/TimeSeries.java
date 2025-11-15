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

/**
 * A basic in-memory time-series data structure that stores {@link TemporalScalar} values
 * in time-sorted order and provides linear interpolation for querying values at arbitrary timestamps.
 *
 * <p>{@link TimeSeries} uses a {@link TreeSet} to maintain temporal scalars in sorted order by time,
 * and a {@link HashMap} for O(1) lookup by exact timestamp. When values are added at existing
 * timestamps, they are accumulated rather than replaced.</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li><strong>Add:</strong> Insert time-value pairs, automatically maintaining sorted order</li>
 *   <li><strong>Query:</strong> Retrieve interpolated values at any timestamp</li>
 *   <li><strong>Purge:</strong> Remove all entries before a specified time</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Basic Time-Series Operations</h3>
 * <pre>{@code
 * TimeSeries series = new TimeSeries();
 *
 * // Add data points
 * series.add(new TemporalScalar(0.0, 1.0));
 * series.add(new TemporalScalar(1.0, 3.0));
 * series.add(new TemporalScalar(2.0, 2.0));
 *
 * // Query with linear interpolation
 * TemporalScalar value = series.valueAt(0.5);
 * System.out.println(value.getValue());  // 2.0 (linear interpolation between 1.0 and 3.0)
 *
 * // Query at exact timestamp
 * TemporalScalar exact = series.valueAt(1.0);
 * System.out.println(exact.getValue());  // 3.0
 * }</pre>
 *
 * <h3>Value Accumulation</h3>
 * <pre>{@code
 * TimeSeries series = new TimeSeries();
 * series.add(new TemporalScalar(1.0, 5.0));
 * series.add(new TemporalScalar(1.0, 3.0));  // Same timestamp
 *
 * TemporalScalar value = series.valueAt(1.0);
 * System.out.println(value.getValue());  // 8.0 (5.0 + 3.0)
 * }</pre>
 *
 * <h3>Purging Old Data</h3>
 * <pre>{@code
 * TimeSeries series = new TimeSeries();
 * series.add(new TemporalScalar(0.0, 1.0));
 * series.add(new TemporalScalar(1.0, 2.0));
 * series.add(new TemporalScalar(2.0, 3.0));
 * series.add(new TemporalScalar(3.0, 4.0));
 *
 * // Remove all data before time 2.0
 * series.purge(2.0);
 *
 * // Only data at t >= 2.0 remains
 * }</pre>
 *
 * <h2>Linear Interpolation</h2>
 * <p>When querying a timestamp that falls between two data points, {@link TimeSeries} performs
 * linear interpolation:</p>
 * <pre>
 * value(t) = v1 + (t - t1) / (t2 - t1) * (v2 - v1)
 * </pre>
 * <p>where (t1, v1) is the nearest point before t, and (t2, v2) is the nearest point after t.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link TimeSeries} uses synchronized methods for thread-safe concurrent access. However, for
 * high-performance scenarios or hardware acceleration, consider using {@link AcceleratedTimeSeries}
 * instead.</p>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>All data is stored in main memory (no paging or overflow to disk)</li>
 *   <li>No hardware acceleration support</li>
 *   <li>Synchronized access may limit concurrent throughput</li>
 *   <li>For large datasets or real-time processing, {@link AcceleratedTimeSeries} is recommended</li>
 * </ul>
 *
 * <h2>Comparison with AcceleratedTimeSeries</h2>
 * <table border="1">
 * <tr><th>Feature</th><th>TimeSeries</th><th>AcceleratedTimeSeries</th></tr>
 * <tr><td>Storage</td><td>TreeSet + HashMap</td><td>PackedCollection (GPU-accessible)</td></tr>
 * <tr><td>Thread Safety</td><td>Synchronized</td><td>Manual synchronization required</td></tr>
 * <tr><td>Hardware Acceleration</td><td>No</td><td>Yes (GPU/OpenCL/Metal)</td></tr>
 * <tr><td>Interpolation</td><td>CPU only</td><td>Can run on GPU</td></tr>
 * <tr><td>Use Case</td><td>Small datasets, prototyping</td><td>Large datasets, real-time processing</td></tr>
 * </table>
 *
 * @see TemporalScalar
 * @see AcceleratedTimeSeries
 *
 * @author Michael Murray
 */
public class TimeSeries {
	private TreeSet<TemporalScalar> sorted;
	private Map<Double, TemporalScalar> byTime;

	/**
	 * Constructs an empty time-series.
	 */
	public TimeSeries() {
		this.sorted = new TreeSet<>(Comparator.comparing(TemporalScalar::getTime));
		this.byTime = new HashMap<>();
	}

	/**
	 * Adds a temporal scalar to this time-series.
	 *
	 * <p>If a value already exists at the specified timestamp, the new value is
	 * <strong>accumulated</strong> (added) to the existing value rather than replacing it.</p>
	 *
	 * <h3>Accumulation Example</h3>
	 * <pre>{@code
	 * series.add(new TemporalScalar(1.0, 5.0));
	 * series.add(new TemporalScalar(1.0, 3.0));
	 * // Value at time 1.0 is now 8.0 (5.0 + 3.0)
	 * }</pre>
	 *
	 * @param value The temporal scalar to add
	 */
	public synchronized void add(TemporalScalar value) {
		if (byTime.containsKey(value.getTime())) {
			TemporalScalar s = byTime.get(value.getTime());
			s.setValue(s.getValue() + value.getValue());
			return;
		}

		sorted.add(value);
		byTime.put(value.getTime(), value);
	}

	/**
	 * Removes all temporal scalars with timestamps strictly less than the specified time.
	 *
	 * <p>This operation is useful for implementing sliding time windows or freeing memory
	 * from old data that is no longer needed.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * series.add(new TemporalScalar(0.0, 1.0));
	 * series.add(new TemporalScalar(1.0, 2.0));
	 * series.add(new TemporalScalar(2.0, 3.0));
	 *
	 * series.purge(2.0);  // Removes entries at 0.0 and 1.0
	 * // Only entry at 2.0 remains
	 * }</pre>
	 *
	 * @param time The cutoff timestamp; all entries with time < this value are removed
	 */
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

	/**
	 * Retrieves the value at the specified timestamp using linear interpolation.
	 *
	 * <p>This method performs the following steps:</p>
	 * <ol>
	 *   <li>Find the temporal scalars immediately before and after the query time</li>
	 *   <li>If an exact match exists, return it directly</li>
	 *   <li>Otherwise, linearly interpolate between the surrounding points</li>
	 * </ol>
	 *
	 * <h3>Interpolation Formula</h3>
	 * <pre>
	 * value(t) = v1 + (t - t1) / (t2 - t1) * (v2 - v1)
	 * </pre>
	 *
	 * <h3>Edge Cases</h3>
	 * <ul>
	 *   <li>Returns {@code null} if no data points exist before or after the query time</li>
	 *   <li>Returns {@code null} if the query time is before all data points</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * series.add(new TemporalScalar(0.0, 1.0));
	 * series.add(new TemporalScalar(2.0, 3.0));
	 *
	 * TemporalScalar result = series.valueAt(1.0);
	 * System.out.println(result.getValue());  // 2.0 (interpolated)
	 * }</pre>
	 *
	 * @param time The timestamp to query
	 * @return A new {@link TemporalScalar} with the interpolated value, or {@code null} if
	 *         interpolation is not possible
	 */
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
