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

public class BasicDelayCell extends SummationCell implements CodeFeatures {
	public static int bufferDuration = 10;
	
	private final double[] buffer = new double[bufferDuration * OutputLine.sampleRate];
	private int cursor;
	private int delay;
	
	private Updatable updatable;
	
	public BasicDelayCell(int delay) {
		setDelay(delay);
	}

	public synchronized void setDelay(int msec) {
		this.delay = (int) ((msec / 1000d) * OutputLine.sampleRate);
	}

	public synchronized int getDelay() { return 1000 * delay / OutputLine.sampleRate; }

	public synchronized void setDelayInFrames(long frames) {
		if (frames != delay) System.out.println("Delay frames: " + frames);
		this.delay = (int) frames;
		if (delay <= 0) delay = 1;
	}

	public synchronized long getDelayInFrames() { return this.delay; }

	public synchronized Position getPosition() {
		Position p = new Position();
		if (delay == 0) delay = 1;
		p.pos = (cursor % delay) / (double) delay;
		p.value = buffer[cursor];
		return p;
	}
	
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

	public static class Position {
		public double pos;
		public double value;
	}
}