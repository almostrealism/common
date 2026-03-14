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

public class AdjustableDelayCell extends SummationCell implements TemporalFeatures, Destroyable {
	public static double defaultPurgeFrequency = 1.0;

	private final int sampleRate;
	private final AcceleratedTimeSeries buffer;
	private CursorPair cursors;

	private final Producer<PackedCollection> delay;
	private final Producer<PackedCollection> scale;

	public AdjustableDelayCell(int sampleRate, double delay) {
		this(sampleRate, Ops.o().c(delay));
	}

	public AdjustableDelayCell(int sampleRate, Producer<PackedCollection> delay) {
		this(sampleRate, delay, Ops.o().c(1.0));
	}

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

	protected void initCursors() {
		cursors = new CursorPair();
	}

	public CursorPair getCursors() { return cursors; }

	public AcceleratedTimeSeries getBuffer() { return buffer; }

	public Producer<PackedCollection> getDelay() { return delay; }

	public Producer<PackedCollection> getScale() { return scale; }

	protected Producer<PackedCollection> getLeftCursor()	{
		return p(cursors.range(shape(1)));
	}

	protected Producer<PackedCollection> getRightCursor() {
		return p(cursors.range(shape(1), 1));
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("AdjustableDelayCell Setup");
		setup.add(super.setup());
		setup.add(a(1, getLeftCursor(), c(0.0)));
		setup.add(a(1, getRightCursor(), c(sampleRate).multiply(getDelay())));
		return setup;
	}

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
