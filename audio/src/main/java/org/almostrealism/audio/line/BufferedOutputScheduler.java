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

package org.almostrealism.audio.line;

import io.almostrealism.util.NumberFormats;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.sources.AudioBuffer;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.time.TimingRegularizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class BufferedOutputScheduler implements CellFeatures {
	public static final long timingPad = -3;

	public static boolean enableVerbose = false;
	public static int logRate = 1024;

	private final Consumer<Runnable> executor;
	private final TemporalRunner process;
	private final InputLine input;
	private final OutputLine output;
	private final AudioBuffer buffer;

	private TimingRegularizer regularizer;
	private Runnable next;

	private long start;
	private boolean stopped;
	private long count;

	private final double rate;
	private final int groupSize;
	private int lastReadPosition;
	private long groupStart;

	private boolean paused;
	private long lastPause, totalPaused;

	protected BufferedOutputScheduler(
			Consumer<Runnable> executor, TemporalRunner process,
			InputLine input, OutputLine output, AudioBuffer buffer) {
		this.executor = executor;
		this.process = process;
		this.input = input;
		this.output = output;
		this.buffer = buffer;
		this.rate = BufferDefaults.bufferingRate;
		this.groupSize = output.getBufferSize() / BufferDefaults.groups;
	}

	public void start() {
		if (next != null) {
			throw new UnsupportedOperationException();
		}

		process.setup().get().run();

		next = getOperations().get();
		regularizer = new TimingRegularizer((long) (buffer.getDetails().getDuration() * 10e9));

		executor.accept(this::run);
	}

	protected Supplier<Runnable> getOperations() {
		OperationList operations = new OperationList("BufferedOutputScheduler");

		if (input == null) {
			warn("No input line");
		} else {
			operations.add(input.read(p(buffer.getInputBuffer())));
		}

		operations.add(process.tick());

		if (output == null) {
			warn("No output line");
		} else {
			operations.add(output.write(p(buffer.getOutputBuffer())));
		}

		return operations;
	}

	public void pause() {
		if (paused) {
			throw new UnsupportedOperationException();
		}

		synchronized (this) {
			int lastRead = lastReadPosition;
			lastReadPosition = output.getReadPosition();
			int diff = lastReadPosition - lastRead;
			if (diff < 0) diff = diff + output.getBufferSize();

			double avg = regularizer.getAverageDuration() / 10e9;
			double tot = (System.currentTimeMillis() - groupStart) / 1000.0;
			double dur = groupSize / (double) output.getSampleRate();

			if (enableVerbose || count % logRate == 0) {
				log("Pausing at " + count + " - " + tot + " (x" +
						NumberFormats.formatNumber(dur / tot) + ") | group " + getLastGroup());
				log("Frames [" + diff + "/" + groupSize + "] (sleep " +
						NumberFormats.formatNumber(getTarget(true) / 1000.0) + ")");
			}

			lastPause = System.currentTimeMillis();
			paused = true;
			notifyAll();
		}
	}

	public void resume() throws InterruptedException {
		if (!paused) {
			log("Waiting");
			wait();
		}

		if (lastPause > 0)
			totalPaused = totalPaused + (System.currentTimeMillis() - lastPause);

		lastPause = 0;
		groupStart = System.currentTimeMillis();
		paused = false;
	}

	public int getWritePosition() {
		return (int) ((count * buffer.getDetails().getFrames()) % output.getBufferSize());
	}

	public int getLastGroup() {
		return lastReadPosition / groupSize;
	}

	protected void attemptAutoResume() {
		if (!paused) return;

		boolean safe = BufferDefaults.isSafeGroup(
				getWritePosition(), output.getReadPosition(),
				groupSize, output.getBufferSize());

		if (safe) {
			try {
				resume();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public void stop() { stopped = true; }

	public AudioBuffer getBuffer() { return buffer; }
	public InputLine getInputLine() { return input; }
	public OutputLine getOutputLine() { return output; }

	protected long toRealTime(double t) { return (long) (t * rate); }
	protected long fromRealTime(double t) { return (long) (t / rate); }

	public long getRenderedCount() { return count; }
	public long getRenderedFrames() { return count * buffer.getDetails().getFrames(); }
	public long getRealTime() { return toRealTime(System.currentTimeMillis() - start - totalPaused); }
	public long getRenderedTime() { return getRenderedFrames() * 1000 / output.getSampleRate(); }
	public long getRenderingGap() { return getRenderedTime() - getRealTime(); }

	protected long getTarget() {
		return getTarget(paused);
	}

	protected long getTarget(boolean paused) {
		long target = fromRealTime(regularizer.getTimingDifference() / 10e6);

		if (paused) {
			target = target / 4;
		} else {
			long gap = Math.max(0, getRenderingGap()) / 2;
			target = target + gap + timingPad;
		}

		return target < 1 ? 1 : target;
	}

	protected void run() {
		start = System.currentTimeMillis();
		long lastDuration = 0;

		while (!stopped) {
			long target;

			attemptAutoResume();

			if (!paused) {
				long s = System.nanoTime();
				regularizer.addMeasuredDuration(lastDuration);
				next.run();
				count++;

				if (getRenderedFrames() % groupSize == 0) {
					pause();
				}

				target = getTarget();
				lastDuration = System.nanoTime() - s;

				if (enableVerbose && count % logRate == 0) {
					log("Active cycle " + count +
							" | render=" + NumberFormats.formatNumber(lastDuration / 10e6) + "ms" +
							" | sleep=" + target + "ms" +
							" | gap=" + getRenderingGap() + "ms" +
							" | wp=" + getWritePosition() +
							" | rp=" + output.getReadPosition());
				}
			} else {
				target = getTarget();

				if (enableVerbose && count % logRate == 0) {
					log("Paused cycle " + count +
							" | sleep=" + target + "ms" +
							" | wp=" + getWritePosition() +
							" | rp=" + output.getReadPosition());
				}
			}

			try {
				Thread.sleep(target);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		log("Stopped");
	}

	public static BufferedOutputScheduler create(ExecutorService executor,
												 InputLine input, OutputLine output,
										 	 	 AudioLineOperation source) {
		return create(executor, input, output, output.getBufferSize() / BufferDefaults.batchCount, source);
	}

	public static BufferedOutputScheduler create(InputLine input, OutputLine output, CellList source) {
		return create(input, output, source.toLineOperation());
	}

	public static BufferedOutputScheduler create(InputLine input, OutputLine output,
												 AudioLineOperation source) {
		return create(input, output,
				output.getBufferSize() / BufferDefaults.batchCount,
				source);
	}

	public static BufferedOutputScheduler create(InputLine input, OutputLine output, int frames,
												 CellList source) {
		return create(Executors.newSingleThreadExecutor(),
						input, output, frames,
						source.toLineOperation());
	}

	public static BufferedOutputScheduler create(InputLine input, OutputLine output, int frames,
												 AudioLineOperation source) {
		return create(Executors.newSingleThreadExecutor(),
						input, output, frames, source);
	}

	public static BufferedOutputScheduler create(ExecutorService executor,
												 InputLine input, OutputLine output, int frames,
												 AudioLineOperation source) {
		return create(executor, input, output,
				AudioBuffer.create(output.getSampleRate(), frames),
				source);
	}

	public static BufferedOutputScheduler create(ExecutorService executor,
												 InputLine input, OutputLine output,
												 AudioBuffer buffer, AudioLineOperation source) {
		return new BufferedOutputScheduler(
				executor::execute,
				source.process(buffer),
				input, output, buffer);
	}
}
