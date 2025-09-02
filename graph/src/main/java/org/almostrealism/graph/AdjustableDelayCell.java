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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.Ops;
import org.almostrealism.time.TemporalFeatures;

import java.util.function.Supplier;

public class AdjustableDelayCell extends SummationCell implements TemporalFeatures {
	public static double defaultPurgeFrequency = 1.0;

	private final int sampleRate;
	private final AcceleratedTimeSeries buffer;
	private CursorPair cursors;

	private final Producer<PackedCollection<?>> delay;
	private final Producer<PackedCollection<?>> scale;

	public AdjustableDelayCell(int sampleRate, double delay) {
		this(sampleRate, Ops.o().c(delay));
	}

	public AdjustableDelayCell(int sampleRate, Producer<PackedCollection<?>> delay) {
		this(sampleRate, delay, Ops.o().c(1.0));
	}

	public AdjustableDelayCell(int sampleRate,
							   Producer<PackedCollection<?>> delay,
							   Producer<PackedCollection<?>> scale) {
		this.sampleRate = sampleRate;
		initCursors();
		buffer = AcceleratedTimeSeries.defaultSeries();
		this.delay = delay;
		this.scale = scale;
	}

	protected void initCursors() {
		cursors = new CursorPair();
	}

	public CursorPair getCursors() { return cursors; }

	public AcceleratedTimeSeries getBuffer() { return buffer; }

	public Producer<PackedCollection<?>> getDelay() { return delay; }

	public Producer<PackedCollection<?>> getScale() { return scale; }

	protected Producer<PackedCollection<?>> getLeftCursor()	{
		return p(cursors.range(shape(1)));
	}

	protected Producer<PackedCollection<?>> getRightCursor() {
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
		tick.add(buffer.add(temporal(r(p(cursors)), (Producer) p(getCachedValue()))));
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
}
