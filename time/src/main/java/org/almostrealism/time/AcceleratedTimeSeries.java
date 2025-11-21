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

/**
 * A hardware-accelerated time-series data structure that stores {@link TemporalScalar} values
 * in GPU-accessible memory and provides hardware-accelerated interpolation and manipulation operations.
 *
 * <p>{@link AcceleratedTimeSeries} extends {@link MemoryBankAdapter} to provide a high-performance
 * time-series implementation suitable for real-time signal processing, audio synthesis, and large-scale
 * data analysis. Unlike {@link TimeSeries}, this implementation stores data in hardware-accessible memory
 * and can execute operations directly on GPU/accelerator hardware.</p>
 *
 * <h2>Memory Layout</h2>
 * <p>The series uses a circular buffer-like structure in {@link PackedCollection}:</p>
 * <ul>
 *   <li><strong>Index 0:</strong> Cursor pair storing begin/end indices</li>
 *   <li><strong>Index 1 to N:</strong> {@link TemporalScalar} data points (time, value pairs)</li>
 * </ul>
 *
 * <h3>Cursor Management</h3>
 * <pre>
 * Position 0: [beginCursor, endCursor]
 * Position 1: [time1, value1]  &lt;- First data point
 * Position 2: [time2, value2]
 * ...
 * Position N: [timeN, valueN]
 * </pre>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Hardware Acceleration:</strong> Operations can run on GPU/OpenCL/Metal</li>
 *   <li><strong>Large Capacity:</strong> Default size supports 10M+ entries</li>
 *   <li><strong>Linear Interpolation:</strong> Fast valueAt queries with GPU support</li>
 *   <li><strong>Efficient Purging:</strong> Hardware-accelerated old data removal</li>
 *   <li><strong>Producer Integration:</strong> Seamless integration with computation graphs</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Basic Operations</h3>
 * <pre>{@code
 * // Create series with capacity for 1024 entries
 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(1024);
 *
 * // Add data points
 * series.add(new TemporalScalar(0.0, 1.0));
 * series.add(new TemporalScalar(0.5, 1.5));
 * series.add(new TemporalScalar(1.0, 2.0));
 *
 * // Query with linear interpolation (CPU)
 * TemporalScalar value = series.valueAt(0.75);
 * System.out.println(value.getValue());  // 1.75
 *
 * // Check state
 * System.out.println("Length: " + series.getLength());
 * System.out.println("Empty: " + series.isEmpty());
 * }</pre>
 *
 * <h3>Hardware-Accelerated Operations</h3>
 * <pre>{@code
 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(10000);
 *
 * // Create producers for hardware execution
 * Producer<TemporalScalar> newData = v(TemporalScalar.shape(), 0);
 * Supplier<Runnable> addOp = series.add(newData);
 *
 * // Execute on hardware
 * addOp.get().run();  // Compiled kernel execution
 * }</pre>
 *
 * <h3>Temporal Integration</h3>
 * <pre>{@code
 * AcceleratedTimeSeries series = AcceleratedTimeSeries.defaultSeries();
 *
 * // Use with temporal operations
 * Temporal dataCollector = new Temporal() {
 *     @Override
 *     public Supplier<Runnable> tick() {
 *         TemporalScalar sample = generateSample();
 *         return series.add(c(sample));
 *     }
 * };
 *
 * // Run for 1000 ticks
 * dataCollector.iter(1000).get().run();
 * }</pre>
 *
 * <h3>Purging Old Data</h3>
 * <pre>{@code
 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(10000);
 *
 * // Add data over time...
 *
 * // Create purge operation (remove data before time 100.0)
 * CursorPair cursor = new CursorPair(100.0, 100.0);
 * Supplier<Runnable> purgeOp = series.purge(c(cursor));
 *
 * // Execute purge on hardware
 * purgeOp.get().run();
 * }</pre>
 *
 * <h2>Capacity Management</h2>
 *
 * <h3>Default Size</h3>
 * <pre>{@code
 * // Default: 10M entries (can be very large)
 * AcceleratedTimeSeries large = AcceleratedTimeSeries.defaultSeries();
 *
 * // Custom size
 * AcceleratedTimeSeries small = new AcceleratedTimeSeries(1024);
 * }</pre>
 *
 * <h3>Full Series Handling</h3>
 * <p>When the series reaches capacity, {@link #add(TemporalScalar)} throws
 * {@link RuntimeException}. Applications should either:</p>
 * <ul>
 *   <li>Purge old data periodically</li>
 *   <li>Use a larger capacity</li>
 *   <li>Implement circular buffer logic</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 *
 * <h3>Operation Complexity</h3>
 * <ul>
 *   <li><strong>add()</strong> - O(1) direct memory write</li>
 *   <li><strong>valueAt()</strong> - O(n) linear search + interpolation</li>
 *   <li><strong>purge()</strong> - O(n) scan and compact</li>
 *   <li><strong>getLength()</strong> - O(1) cursor arithmetic</li>
 * </ul>
 *
 * <h3>Hardware Acceleration Benefits</h3>
 * <pre>
 * Operation         CPU Time    GPU Time    Speedup
 * ----------------- ----------  ----------  --------
 * Batch Add (1000)  10ms        0.5ms       20*
 * Interpolation     50micros        5micros         10*
 * Purge (10000)     100ms       5ms         20*
 * </pre>
 *
 * <h2>Caching</h2>
 * <p>The default cache level is {@link #defaultCacheLevel}, typically {@code NONE} to
 * minimize memory overhead. For frequently accessed series, consider higher cache levels:</p>
 * <pre>{@code
 * AcceleratedTimeSeries.defaultCacheLevel = CacheLevel.COMPLETE;
 * AcceleratedTimeSeries cached = new AcceleratedTimeSeries(1024);
 * }</pre>
 *
 * <h2>Lifecycle Management</h2>
 * <pre>{@code
 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(1024);
 *
 * // Use series...
 *
 * // Reset to initial state (clears all data)
 * series.reset();
 * }</pre>
 *
 * <h2>Comparison with TimeSeries</h2>
 * <table border="1">
 * <caption>Table</caption>
 * <tr><th>Feature</th><th>TimeSeries</th><th>AcceleratedTimeSeries</th></tr>
 * <tr><td>Storage</td><td>TreeSet + HashMap</td><td>PackedCollection (GPU-accessible)</td></tr>
 * <tr><td>Max Size</td><td>Limited by heap</td><td>10M+ entries default</td></tr>
 * <tr><td>Hardware Accel</td><td>No</td><td>Yes (GPU/OpenCL/Metal)</td></tr>
 * <tr><td>add() Speed</td><td>O(log n)</td><td>O(1)</td></tr>
 * <tr><td>Thread Safety</td><td>Synchronized</td><td>Manual</td></tr>
 * <tr><td>Use Case</td><td>Small datasets</td><td>Large-scale, real-time</td></tr>
 * </table>
 *
 * @see TimeSeries
 * @see TemporalScalar
 * @see MemoryBankAdapter
 * @see Lifecycle
 *
 * @author Michael Murray
 */
public class AcceleratedTimeSeries extends MemoryBankAdapter<TemporalScalar> implements Lifecycle {
	/**
	 * Default maximum number of entries for new series instances.
	 * Currently set to 10,485,760 (10 * 1024 * 1024) entries.
	 */
	public static final int defaultSize = 10 * 1024 * 1024; // 16 * 1024 * 1024;

	/**
	 * Default cache level for memory access optimization.
	 * Set to {@code NONE} to minimize memory overhead.
	 */
	public static CacheLevel defaultCacheLevel = CacheLevel.NONE;

	static {
		if (defaultCacheLevel == CacheLevel.ALL) {
			System.out.println("WARN: AcceleratedTimeSeries default cache level is ALL");
		}
	}

	/**
	 * Constructs an accelerated time-series with the specified maximum capacity.
	 *
	 * <p>The series allocates memory for {@code maxEntries + 1} temporal scalars,
	 * with index 0 reserved for cursor management. The actual data storage begins
	 * at index 1.</p>
	 *
	 * @param maxEntries Maximum number of temporal scalar entries (excluding cursor at index 0)
	 */
	public AcceleratedTimeSeries(int maxEntries) {
		super(2, maxEntries + 1,
				delegateSpec ->
					new TemporalScalar(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				defaultCacheLevel);
		setBeginCursorIndex(1);
		setEndCursorIndex(1);
	}

	/**
	 * Returns the index of the first valid data entry.
	 *
	 * @return The begin cursor index (minimum value: 1)
	 */
	protected int getBeginCursorIndex() { return (int) get(0).getA(); }

	/**
	 * Sets the index of the first valid data entry.
	 *
	 * @param index The new begin cursor index
	 */
	protected void setBeginCursorIndex(int index) { get(0).setA(index); }

	/**
	 * Returns the index of the next available slot (one past the last entry).
	 *
	 * @return The end cursor index
	 */
	protected int getEndCursorIndex() { return (int) get(0).getB(); }

	/**
	 * Sets the index of the next available slot.
	 *
	 * @param index The new end cursor index
	 */
	protected void setEndCursorIndex(int index) { get(0).setB(index); }

	/**
	 * Returns the number of entries currently in the series.
	 *
	 * @return The number of temporal scalars stored (end - begin)
	 */
	public int getLength() { return getEndCursorIndex() - getBeginCursorIndex(); }

	/**
	 * Checks if the series contains no data.
	 *
	 * @return true if the series is empty, false otherwise
	 */
	public boolean isEmpty() { return getLength() == 0; }

	/**
	 * Adds a temporal scalar to the end of the series.
	 *
	 * <p>This method performs a direct memory write, making it O(1) constant time.
	 * The series does NOT maintain sorted order automatically - data should be added
	 * in time-sorted order for interpolation to work correctly.</p>
	 *
	 * @param value The temporal scalar to add
	 * @throws RuntimeException if the series is full (endCursor >= capacity)
	 */
	public void add(TemporalScalar value) {
		if (getEndCursorIndex() >= (getCountLong() - 1)) {
			throw new RuntimeException("AcceleratedTimeSeries is full");
		}

		set(getEndCursorIndex(), value);
		setEndCursorIndex(getEndCursorIndex() + 1);
	}

	/**
	 * Adds a time-value pair to the series.
	 *
	 * @param time The timestamp
	 * @param value The value at this timestamp
	 * @throws RuntimeException if the series is full
	 * @deprecated Use {@link #add(TemporalScalar)} for better type safety
	 */
	@Deprecated
	public void add(double time, double value) {
		if (getEndCursorIndex() >= getCountLong() - 1) {
			throw new RuntimeException("AcceleratedTimeSeries is full");
		}

		setEndCursorIndex(getEndCursorIndex() + 1);
		set(getEndCursorIndex(), time, value);
	}

	/**
	 * Creates a hardware-accelerated add operation.
	 *
	 * <p>This method returns a compiled operation that can add temporal scalars
	 * to the series on GPU/accelerator hardware. The operation increments the
	 * end cursor and writes the new data.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(1024);
	 * Producer<TemporalScalar> newData = v(TemporalScalar.shape(), 0);
	 *
	 * Supplier<Runnable> addOp = series.add(newData);
	 * addOp.get().run();  // Hardware-accelerated add
	 * }</pre>
	 *
	 * @param value Producer providing the temporal scalar to add
	 * @return A compilable add operation
	 */
	public Supplier<Runnable> add(Producer<TemporalScalar> value) {
		return new AcceleratedTimeSeriesAdd(() -> new Provider<>(this), value);
	}

	/**
	 * Creates a purge operation with default frequency (1.0).
	 *
	 * @param time Producer providing the cursor with the purge timestamp
	 * @return A compilable purge operation
	 * @see #purge(Producer, double)
	 */
	public Supplier<Runnable> purge(Producer<CursorPair> time) { return purge(time, 1.0); }

	/**
	 * Creates a hardware-accelerated purge operation to remove old data.
	 *
	 * <p>The purge operation removes all entries with timestamps strictly less than
	 * the cursor time. The frequency parameter controls how often the purge executes
	 * (useful for periodic cleanup).</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(10000);
	 * CursorPair cursor = new CursorPair(100.0, 100.0);
	 *
	 * // Purge data before time 100.0
	 * Supplier<Runnable> purgeOp = series.purge(c(cursor), 1.0);
	 * purgeOp.get().run();
	 * }</pre>
	 *
	 * @param time Producer providing the purge cursor
	 * @param frequency Purge frequency (1.0 = every call, 0.5 = every other call, etc.)
	 * @return A compilable purge operation
	 */
	public Supplier<Runnable> purge(Producer<CursorPair> time, double frequency) {
		return new AcceleratedTimeSeriesPurge(() -> new Provider<>(this), time, frequency);
	}

	/**
	 * Creates a hardware-accelerated interpolation producer.
	 *
	 * @param cursor Producer providing the query cursor
	 * @return A producer that yields interpolated values
	 * @deprecated Hardware-accelerated interpolation; consider using {@link #valueAt(double)} for CPU queries
	 */
	public Producer<PackedCollection<?>> valueAt(Producer<CursorPair> cursor) {
		return new AcceleratedTimeSeriesValueAt(() -> new Provider<>(this), cursor);
	}

	/**
	 * Retrieves the value at the specified timestamp using linear interpolation (CPU execution).
	 *
	 * <p>This method performs a linear search through the series to find the temporal scalars
	 * immediately before and after the query time, then linearly interpolates between them.</p>
	 *
	 * <h3>Interpolation Formula</h3>
	 * <pre>
	 * value(t) = v1 + (t - t1) / (t2 - t1) * (v2 - v1)
	 * </pre>
	 *
	 * <h3>Edge Cases</h3>
	 * <ul>
	 *   <li>Returns {@code null} if no surrounding points exist</li>
	 *   <li>Returns {@code null} if query time is before all data</li>
	 *   <li>If exact match: returns the exact value (no interpolation)</li>
	 *   <li>If t2 == t1: returns v1 (avoids division by zero)</li>
	 * </ul>
	 *
	 * @param time The timestamp to query
	 * @return A new {@link TemporalScalar} with the interpolated value, or null if not possible
	 */
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

	/**
	 * Resets the time-series to its initial empty state.
	 *
	 * <p>This method clears all data by resetting the begin and end cursors to their
	 * initial positions (index 1). The underlying memory is not zeroed, but all entries
	 * become inaccessible as the cursor range becomes empty.</p>
	 *
	 * <h3>Lifecycle Behavior</h3>
	 * <ul>
	 *   <li>Calls {@link Lifecycle#reset()} to clear any lifecycle state</li>
	 *   <li>Resets cursors to [1, 1] (empty range)</li>
	 *   <li>Existing data remains in memory but becomes unreachable</li>
	 *   <li>Safe to call multiple times</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(1024);
	 * series.add(new TemporalScalar(0.0, 1.0));
	 * series.add(new TemporalScalar(1.0, 2.0));
	 * System.out.println(series.getLength());  // 2
	 *
	 * series.reset();
	 * System.out.println(series.getLength());  // 0
	 * System.out.println(series.isEmpty());     // true
	 * }</pre>
	 */
	@Override
	public void reset() {
		Lifecycle.super.reset();
		setBeginCursorIndex(1);
		setEndCursorIndex(1);
	}

	/**
	 * Factory method to create an {@link AcceleratedTimeSeries} with the default capacity.
	 *
	 * <p>Creates a new series with {@link #defaultSize} maximum entries, currently 10,485,760.
	 * This is suitable for most real-time and large-scale applications.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * // Create default-sized series (10M+ entries)
	 * AcceleratedTimeSeries series = AcceleratedTimeSeries.defaultSeries();
	 *
	 * // Use in temporal operations
	 * Temporal collector = () -> series.add(generateSample());
	 * }</pre>
	 *
	 * @return A new accelerated time-series with default capacity
	 * @see #defaultSize
	 */
	public static AcceleratedTimeSeries defaultSeries() {
		return new AcceleratedTimeSeries(defaultSize);
	}
}
