/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.audio.filter;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.time.Updatable;

import java.util.function.Supplier;

/**
 * A simple delay line cell with configurable delay time.
 *
 * <p>BasicDelayCell implements a circular buffer delay line that can delay
 * audio signals by a configurable amount of time. It extends {@link SummationCell}
 * to sum incoming signals into the delay buffer.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a 500ms delay
 * BasicDelayCell delay = new BasicDelayCell(500);
 * delay.setReceptor(output);
 * delay.push(inputSignal);
 * }</pre>
 *
 * @see SummationCell
 * @see DelayNetwork
 */
public class BasicDelayCell extends SummationCell implements CodeFeatures {
	/** Maximum delay buffer duration in seconds; determines the size of the circular delay buffer. */
	public static int bufferDuration = 10;

	/** Circular delay buffer storing audio samples for the maximum buffer duration. */
	private final double[] buffer = new double[bufferDuration * OutputLine.sampleRate];

	/** Current write position in the circular delay buffer. */
	private int cursor;

	/** Delay length in frames; the read position is (cursor + delay) % buffer.length. */
	private int delay;

	/** Optional updatable notified on each cursor advancement at the updatable's resolution. */
	private Updatable updatable;

	/**
	 * Creates a BasicDelayCell with the given delay in milliseconds.
	 *
	 * @param delay delay time in milliseconds
	 */
	public BasicDelayCell(int delay) {
		setDelay(delay);
	}

	/**
	 * Sets the delay time in milliseconds.
	 *
	 * @param msec delay time in milliseconds
	 */
	public synchronized void setDelay(int msec) {
		this.delay = (int) ((msec / 1000d) * OutputLine.sampleRate);
	}

	/** Returns the delay time in milliseconds. */
	public synchronized int getDelay() { return 1000 * delay / OutputLine.sampleRate; }

	/**
	 * Sets the delay time in frames.
	 *
	 * @param frames delay length in frames; values less than or equal to 0 are clamped to 1
	 */
	public synchronized void setDelayInFrames(long frames) {
		if (frames != delay) log("Delay frames: " + frames);
		this.delay = (int) frames;
		if (delay <= 0) delay = 1;
	}

	/** Returns the delay time in frames. */
	public synchronized long getDelayInFrames() { return this.delay; }

	/**
	 * Returns the current read position within the delay cycle and the sample value at that position.
	 *
	 * @return a Position with the normalized position (0–1) and current sample value
	 */
	public synchronized Position getPosition() {
		Position p = new Position();
		if (delay == 0) delay = 1;
		p.pos = (cursor % delay) / (double) delay;
		p.value = buffer[cursor];
		return p;
	}
	
	/**
	 * Sets the updatable to be notified at each tick at its configured resolution.
	 *
	 * @param ui the updatable listener, or null to remove the current listener
	 */
	public void setUpdatable(Updatable ui) { this.updatable = ui; }

	@Override
	public synchronized Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection value = new PackedCollection(1);
		Supplier<Runnable> push = super.push(p(value));

		return () -> () -> {
			int dPos = (cursor + delay) % buffer.length;

			this.buffer[dPos] = buffer[dPos] + protein.get().evaluate().toDouble(0);

			value.setMem(buffer[cursor], 1.0);

			if (updatable != null && cursor % updatable.getResolution() == 0) updatable.update();

			this.buffer[cursor] = 0;
			cursor++;
			cursor = cursor % buffer.length;
			push.get().run();
		};
	}

	@Override
	public void reset() {
		super.reset();
		// TODO throw new UnsupportedOperationException();
	}

	/** Holds the read position and sample value at the current cursor location in the delay buffer. */
	public static class Position {
		/** Normalized read position within the delay cycle, in the range [0, 1). */
		public double pos;
		/** Audio sample value at the current cursor position in the delay buffer. */
		public double value;
	}
}