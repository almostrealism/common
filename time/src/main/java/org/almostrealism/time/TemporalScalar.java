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

import org.almostrealism.algebra.Pair;
import org.almostrealism.hardware.MemoryData;

import java.util.function.BiFunction;

/**
 * Represents a single time-value pair in a time-series, storing a timestamp and
 * its associated scalar value.
 *
 * <p>{@link TemporalScalar} is a specialized {@link Pair} where the first component (A)
 * represents time and the second component (B) represents the value at that time. This
 * structure is the fundamental building block for time-series data storage and manipulation
 * in the AlmostRealism framework.</p>
 *
 * <h2>Data Layout</h2>
 * <p>A {@link TemporalScalar} consists of two double-precision values:</p>
 * <ul>
 *   <li><strong>Time (A):</strong> The timestamp, typically in seconds or arbitrary time units</li>
 *   <li><strong>Value (B):</strong> The scalar value at that timestamp</li>
 * </ul>
 *
 * <h2>Memory Representation</h2>
 * <p>As a {@link Pair}, {@link TemporalScalar} can be:</p>
 * <ul>
 *   <li><strong>Standalone:</strong> Created with {@code new TemporalScalar(time, value)}</li>
 *   <li><strong>Memory-backed:</strong> Created as a view into a {@link MemoryData} buffer,
 *       enabling efficient storage in packed collections and hardware-accelerated access</li>
 * </ul>
 *
 * <h2>Usage in Time-Series</h2>
 *
 * <h3>Creating Individual Points</h3>
 * <pre>{@code
 * // Standalone temporal scalar
 * TemporalScalar point = new TemporalScalar(1.5, 3.7);
 * System.out.println("Time: " + point.getTime());   // 1.5
 * System.out.println("Value: " + point.getValue()); // 3.7
 * }</pre>
 *
 * <h3>Adding to Time-Series</h3>
 * <pre>{@code
 * TimeSeries series = new TimeSeries();
 * series.add(new TemporalScalar(0.0, 1.0));
 * series.add(new TemporalScalar(1.0, 2.5));
 * series.add(new TemporalScalar(2.0, 1.8));
 *
 * TemporalScalar interpolated = series.valueAt(1.5);  // Linear interpolation
 * }</pre>
 *
 * <h3>Hardware-Accelerated Access</h3>
 * <pre>{@code
 * // Memory-backed temporal scalars in AcceleratedTimeSeries
 * AcceleratedTimeSeries accelSeries = new AcceleratedTimeSeries(1024);
 * accelSeries.add(new TemporalScalar(0.0, 1.0));
 *
 * // Access as memory-backed view
 * TemporalScalar point = accelSeries.get(1);  // Index 0 is reserved for cursors
 * point.setTime(0.5);
 * point.setValue(1.5);
 * }</pre>
 *
 * <h2>Time Semantics</h2>
 * <p>The interpretation of the time component is context-dependent:</p>
 * <ul>
 *   <li><strong>Audio Processing:</strong> Time in seconds, sample index, or beats</li>
 *   <li><strong>Animation:</strong> Frame number or elapsed time</li>
 *   <li><strong>Simulations:</strong> Simulation time steps</li>
 *   <li><strong>General Time-Series:</strong> Any monotonically increasing time coordinate</li>
 * </ul>
 *
 * <h2>Ordering and Comparison</h2>
 * <p>Time-series structures like {@link TimeSeries} and {@link AcceleratedTimeSeries} expect
 * temporal scalars to be added in time-sorted order. Adding out-of-order points may lead to
 * incorrect interpolation results.</p>
 *
 * <h2>Integration with Hardware Acceleration</h2>
 * <p>When used within {@link AcceleratedTimeSeries}, temporal scalars are stored in
 * hardware-accessible memory, enabling:</p>
 * <ul>
 *   <li>GPU-accelerated interpolation</li>
 *   <li>Efficient bulk operations</li>
 *   <li>Direct memory access from compiled kernels</li>
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li>For large time-series, prefer {@link AcceleratedTimeSeries} over {@link TimeSeries}
 *       to leverage hardware acceleration</li>
 *   <li>Memory-backed temporal scalars are more efficient than standalone instances when
 *       working with collections</li>
 *   <li>The time component should be monotonically increasing for optimal performance in
 *       sorted data structures</li>
 * </ul>
 *
 * @see TimeSeries
 * @see AcceleratedTimeSeries
 * @see Pair
 *
 * @author Michael Murray
 */
public class TemporalScalar extends Pair {
	/**
	 * Constructs an uninitialized temporal scalar with time and value both set to 0.0.
	 */
	public TemporalScalar() { }

	/**
	 * Constructs a temporal scalar with the specified time and value.
	 *
	 * @param time The timestamp for this data point
	 * @param value The scalar value at this timestamp
	 */
	public TemporalScalar(double time, double value) {
		setTime(time);
		setValue(value);
	}

	/**
	 * Constructs a temporal scalar as a view into an existing memory buffer.
	 *
	 * <p>This constructor is used internally by {@link AcceleratedTimeSeries} and other
	 * memory-backed collections to create temporal scalar views without copying data.</p>
	 *
	 * @param delegate The underlying memory buffer
	 * @param delegateOffset The offset in the buffer where this temporal scalar's data begins
	 */
	public TemporalScalar(MemoryData delegate, int delegateOffset) {
		super(delegate, delegateOffset);
	}

	/**
	 * Returns the timestamp of this temporal scalar.
	 *
	 * @return The time component (first element of the pair)
	 */
	public double getTime() { return getA(); }

	/**
	 * Sets the timestamp of this temporal scalar.
	 *
	 * @param time The new time value
	 */
	public void setTime(double time) { setA(time); }

	/**
	 * Returns the scalar value at this timestamp.
	 *
	 * @return The value component (second element of the pair)
	 */
	public double getValue() { return getB(); }

	/**
	 * Sets the scalar value at this timestamp.
	 *
	 * @param value The new value
	 */
	public void setValue(double value) { setB(value); }

	/**
	 * Returns a postprocessor function for creating temporal scalar views from memory buffers.
	 *
	 * <p>This method is used internally by collection infrastructure to instantiate
	 * {@link TemporalScalar} objects from memory-backed storage.</p>
	 *
	 * @return A function that creates temporal scalar views given memory data and offset
	 */
	public static BiFunction<MemoryData, Integer, Pair> postprocessor() {
		return (delegate, offset) -> new TemporalScalar(delegate, offset);
	}
}
