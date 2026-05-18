/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.graph;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalFeatures;

import java.util.function.Supplier;

/**
 * A {@link SummationCell} that applies a variable-length delay line to incoming audio samples.
 *
 * <p>{@code AdjustableDelayCell} implements an audio delay effect using an
 * {@link AcceleratedTimeSeries} ring buffer. The delay duration and playback
 * scale can be controlled at runtime via producers, making them amenable to
 * hardware-accelerated modulation.</p>
 *
 * <p>On each {@link #tick()}, the cell:</p>
 * <ol>
 *   <li>Writes the current cached value into the ring buffer at the write cursor</li>
 *   <li>Reads the delayed value at the read cursor position</li>
 *   <li>Purges old buffer entries past the read cursor</li>
 *   <li>Advances both cursors by the scale factor</li>
 *   <li>Resets the cached value and pushes the read value downstream</li>
 * </ol>
 *
 * @see SummationCell
 * @see AcceleratedTimeSeries
 * @author Michael Murray
 */
public class AdjustableDelayCell extends SummationCell implements TemporalFeatures, Destroyable {
	/** Default purge frequency for the ring buffer, in units of cursor advances per tick. */
	public static double defaultPurgeFrequency = 1.0;

	/** Audio sample rate in Hz, used to convert delay in seconds to frames. */
	private final int sampleRate;

	/** Ring buffer storing past samples for the delay line. */
	private final AcceleratedTimeSeries buffer;

	/** Read and write cursor pair for the ring buffer. */
	private CursorPair cursors;

	/** Producer for the delay duration (in seconds). */
	private final Producer<PackedCollection> delay;

	/** Producer for the playback scale factor (1.0 = normal speed). */
	private final Producer<PackedCollection> scale;

	/**
	 * Creates a delay cell with a fixed delay duration in seconds.
	 *
	 * @param sampleRate the audio sample rate in Hz
	 * @param delay      the delay duration in seconds
	 */
	public AdjustableDelayCell(int sampleRate, double delay) {
		this(sampleRate, Ops.o().c(delay));
	}

	/**
	 * Creates a delay cell with a producer-controlled delay duration and default scale of 1.0.
	 *
	 * @param sampleRate the audio sample rate in Hz
	 * @param delay      producer for the delay duration in seconds
	 */
	public AdjustableDelayCell(int sampleRate, Producer<PackedCollection> delay) {
		this(sampleRate, delay, Ops.o().c(1.0));
	}

	/**
	 * Creates a delay cell with producer-controlled delay and scale, using the default buffer size.
	 *
	 * @param sampleRate the audio sample rate in Hz
	 * @param delay      producer for the delay duration in seconds
	 * @param scale      producer for the playback scale factor
	 */
	public AdjustableDelayCell(int sampleRate,
							   Producer<PackedCollection> delay,
							   Producer<PackedCollection> scale) {
		this(sampleRate, delay, scale, AcceleratedTimeSeries.defaultSize);
	}

	/**
	 * Creates a delay cell with a specified buffer capacity.
	 *
	 * <p>The buffer size should be at least {@code sampleRate * maxDelaySeconds * 2}
	 * to accommodate the delay line's read and write cursors. Using a smaller buffer
	 * than the default {@link AcceleratedTimeSeries#defaultSize} dramatically reduces
	 * native memory consumption when the maximum delay is known.</p>
	 *
	 * @param sampleRate audio sample rate
	 * @param delay      delay duration producer (in seconds)
	 * @param scale      playback scale producer
	 * @param bufferSize maximum number of entries in the delay buffer
	 */
	public AdjustableDelayCell(int sampleRate,
							   Producer<PackedCollection> delay,
							   Producer<PackedCollection> scale,
							   int bufferSize) {
		this.sampleRate = sampleRate;
		initCursors();
		buffer = new AcceleratedTimeSeries(bufferSize);
		this.delay = delay;
		this.scale = scale;
	}

	/**
	 * Initializes the cursor pair to its default state.
	 * Subclasses may override to provide a custom cursor implementation.
	 */
	protected void initCursors() {
		cursors = new CursorPair();
	}

	/**
	 * Returns the cursor pair managing read and write positions in the buffer.
	 *
	 * @return the cursor pair
	 */
	public CursorPair getCursors() { return cursors; }

	/**
	 * Returns the ring buffer holding past samples.
	 *
	 * @return the accelerated time series buffer
	 */
	public AcceleratedTimeSeries getBuffer() { return buffer; }

	/**
	 * Returns the producer for the delay duration.
	 *
	 * @return producer providing the delay in seconds
	 */
	public Producer<PackedCollection> getDelay() { return delay; }

	/**
	 * Returns the producer for the playback scale factor.
	 *
	 * @return producer providing the scale value
	 */
	public Producer<PackedCollection> getScale() { return scale; }

	/**
	 * Returns a producer for the left (write) cursor position.
	 *
	 * @return producer for the write cursor value
	 */
	protected Producer<PackedCollection> getLeftCursor()	{
		return p(cursors.range(shape(1)));
	}

	/**
	 * Returns a producer for the right (read) cursor position.
	 *
	 * @return producer for the read cursor value
	 */
	protected Producer<PackedCollection> getRightCursor() {
		return p(cursors.range(shape(1), 1));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Initializes the parent summation cell, sets the write cursor to 0,
	 * and positions the read cursor at {@code sampleRate * delay} frames ahead.</p>
	 *
	 * @return a supplier that performs the setup operations
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("AdjustableDelayCell Setup");
		setup.add(super.setup());
		setup.add(a(1, getLeftCursor(), c(0.0)));
		setup.add(a(1, getRightCursor(), c(sampleRate).multiply(getDelay())));
		return setup;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Writes the cached value to the ring buffer, reads the delayed value,
	 * purges old entries, advances the cursors by the scale factor, resets
	 * the cache, and pushes the delayed value downstream.</p>
	 *
	 * @return a supplier that performs all tick operations
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("AdjustableDelayCell Tick");
		tick.add(buffer.add((Producer) temporal(r(p(cursors)), p(getCachedValue()))));
		tick.add(a(cp(getOutputValue()), buffer.valueAt(p(cursors))));
		tick.add(buffer.purge(p(cursors), defaultPurgeFrequency));
		tick.add(cursors.increment(getScale()));
		tick.add(reset(p(getCachedValue())));
		tick.add(pushValue());
		return tick;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Resets the parent state and clears the ring buffer.</p>
	 */
	@Override
	public void reset() {
		super.reset();
		buffer.reset();
	}

	/**
	 * Releases the native memory held by this delay cell's buffer and cursors.
	 *
	 * <p>The {@link AcceleratedTimeSeries} buffer is the largest allocation
	 * (10M entries by default). Destroying it returns the native memory to
	 * the {@link org.almostrealism.c.NativeMemoryProvider} immediately rather
	 * than waiting for garbage collection.</p>
	 */
	@Override
	public void destroy() {
		buffer.destroy();
		cursors.destroy();
	}
}
