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

/**
 * Manages real-time audio processing by coordinating input reading, audio processing,
 * and output writing with adaptive timing control to prevent buffer underruns and overruns.
 *
 * <h2>Overview</h2>
 * <p>
 * {@link BufferedOutputScheduler} acts as a bridge between audio processing logic and
 * hardware audio lines. It runs a continuous loop that reads from an input line,
 * processes audio through a {@link TemporalRunner}, and writes to an output line,
 * all while maintaining timing that keeps pace with real-time playback.
 * </p>
 *
 * <h2>Buffer Safety Model</h2>
 * <p>
 * The output buffer is logically divided into groups (determined by
 * {@link BufferDefaults#groups}). After filling each group, the scheduler pauses
 * to allow the output line's read position to advance. It automatically resumes
 * when {@link BufferDefaults#isSafeGroup} confirms the read position has moved
 * to a safe location, preventing the write position from overwriting unread data.
 * </p>
 *
 * <h2>Timing Control</h2>
 * <p>
 * Adaptive sleep durations are calculated based on:
 * </p>
 * <ul>
 *   <li>Historical timing data smoothed by {@link TimingRegularizer}</li>
 *   <li>The rendering gap (how far ahead rendering is compared to real-time)</li>
 *   <li>A constant timing pad adjustment ({@link #timingPad})</li>
 * </ul>
 * <p>
 * When paused, the scheduler uses shorter sleep intervals (1/4 of normal) to
 * enable faster detection of safe resume conditions.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create with a CellList source
 * BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
 *     inputLine, outputLine, cellList);
 *
 * // Start processing
 * scheduler.start();
 *
 * // Later, stop processing
 * scheduler.stop();
 * }</pre>
 *
 * @see AudioBuffer
 * @see InputLine
 * @see OutputLine
 * @see TemporalRunner
 * @see TimingRegularizer
 * @see BufferDefaults
 */
public class BufferedOutputScheduler implements CellFeatures {
	/**
	 * Constant adjustment (in milliseconds) applied to sleep duration calculations.
	 * A negative value causes slightly shorter sleeps, helping to stay ahead of
	 * real-time playback and reduce the risk of buffer underruns.
	 */
	public static final long timingPad = -3;

	/**
	 * When {@code true}, enables detailed logging of scheduler state during
	 * active and paused cycles. Useful for debugging timing issues.
	 */
	public static boolean enableVerbose = false;

	/**
	 * Controls how frequently log messages are emitted when {@link #enableVerbose}
	 * is {@code true}. A message is logged every {@code logRate} cycles.
	 */
	public static int logRate = 1024;

	/**
	 * Sleep target threshold (in milliseconds) below which the scheduler enters
	 * degraded mode. When the calculated sleep time falls to or below this value,
	 * the system cannot keep up with real-time audio generation.
	 */
	public static final long DEGRADED_THRESHOLD = 3;

	/**
	 * Number of consecutive cycles with good performance required before exiting
	 * degraded mode. This hysteresis prevents rapid oscillation between modes.
	 */
	public static final int RECOVERY_CYCLES = 4;

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

	private boolean degradedMode;
	private int recoveryCount;

	/**
	 * Creates a new scheduler with the specified components.
	 *
	 * @param executor consumer that accepts and runs the scheduler's main loop
	 * @param process  the temporal runner that performs audio processing each tick
	 * @param input    the input line to read audio data from, or {@code null} if no input
	 * @param output   the output line to write processed audio to
	 * @param buffer   the audio buffer used for intermediate storage between input,
	 *                 processing, and output stages
	 */
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

	/**
	 * Initializes and starts the scheduler's processing loop.
	 * <p>
	 * This method sets up the temporal process, compiles the operations pipeline,
	 * initializes the timing regularizer, and submits the main loop to the executor.
	 * </p>
	 *
	 * @throws UnsupportedOperationException if the scheduler has already been started
	 */
	public void start() {
		if (next != null) {
			throw new UnsupportedOperationException();
		}

		process.setup().get().run();

		next = getOperations().get();
		regularizer = new TimingRegularizer((long) (buffer.getDetails().getDuration() * 10e9));

		executor.accept(this::run);
		log("Started BufferedOutputScheduler");
	}

	/**
	 * Builds the operations pipeline that executes each processing cycle.
	 * <p>
	 * The pipeline consists of three stages:
	 * </p>
	 * <ol>
	 *   <li>Read from input line into the buffer's input buffer (if input is available)</li>
	 *   <li>Tick the temporal process to generate/transform audio</li>
	 *   <li>Write from the buffer's output buffer to the output line (if output is available)</li>
	 * </ol>
	 *
	 * @return a supplier that provides the compiled runnable operation
	 */
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

	/**
	 * Pauses the scheduler after completing a buffer group.
	 * <p>
	 * When paused, the scheduler stops processing new audio but continues
	 * monitoring the output line's read position. It will automatically
	 * resume via {@link #attemptAutoResume()} when safe to continue writing.
	 * </p>
	 *
	 * @throws UnsupportedOperationException if already paused
	 */
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

	/**
	 * Resumes the scheduler from a paused state.
	 * <p>
	 * If not currently paused, this method waits for a pause notification.
	 * Upon resuming, it updates the total paused time tracking and resets
	 * the group timing.
	 * </p>
	 *
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
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

	/**
	 * Returns the current write position within the circular output buffer.
	 *
	 * @return the buffer index where the next audio frame will be written
	 */
	public int getWritePosition() {
		return (int) ((count * buffer.getDetails().getFrames()) % output.getBufferSize());
	}

	/**
	 * Returns the index of the last group that was read from by the output line.
	 *
	 * @return the group index based on the last recorded read position
	 */
	public int getLastGroup() {
		return lastReadPosition / groupSize;
	}

	/**
	 * Returns the number of frames between the current write position and the
	 * hardware read position in the circular buffer.
	 * <p>
	 * A positive value indicates the write position is ahead of the read position
	 * (normal operation - we're writing data before it's played).
	 * A value near zero indicates the read position has nearly caught up to
	 * the write position (buffer underrun risk).
	 * <p>
	 * The value accounts for circular buffer wrap-around.
	 *
	 * @return the buffer gap in frames, or 0 if no output is configured
	 */
	public int getBufferGap() {
		if (output == null) return 0;

		int writePos = getWritePosition();
		int readPos = output.getReadPosition();
		int bufferSize = output.getBufferSize();

		int gap = writePos - readPos;
		if (gap < 0) {
			gap += bufferSize;  // Handle circular buffer wrap-around
		}
		return gap;
	}

	/**
	 * Returns the buffer gap as a percentage of the total buffer size.
	 * <p>
	 * Useful for UI display. Values typically range from 0-100%, where:
	 * <ul>
	 *   <li>~25-75%: Normal operation</li>
	 *   <li>&lt;25%: Risk of underrun (read catching up to write)</li>
	 *   <li>&gt;75%: Risk of overrun (write catching up to read)</li>
	 * </ul>
	 *
	 * @return the buffer gap as a percentage (0.0-100.0)
	 */
	public double getBufferGapPercent() {
		if (output == null) return 0.0;
		return (getBufferGap() * 100.0) / output.getBufferSize();
	}

	/**
	 * Attempts to automatically resume processing if currently paused and safe to do so.
	 * <p>
	 * Safety is determined by {@link BufferDefaults#isSafeGroup}, which checks that
	 * the write position and read position are in different buffer groups, ensuring
	 * no data will be overwritten before it's played.
	 * </p>
	 */
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

	/**
	 * Signals the scheduler to stop processing after the current cycle completes.
	 */
	public void stop() { stopped = true; }

	/**
	 * Returns the audio buffer used for intermediate storage.
	 *
	 * @return the audio buffer
	 */
	public AudioBuffer getBuffer() { return buffer; }

	/**
	 * Returns the input line used for reading audio data.
	 *
	 * @return the input line, or {@code null} if no input is configured
	 */
	public InputLine getInputLine() { return input; }

	/**
	 * Returns the output line used for writing audio data.
	 *
	 * @return the output line
	 */
	public OutputLine getOutputLine() { return output; }

	/**
	 * Returns whether the scheduler is currently in degraded mode.
	 * <p>
	 * Degraded mode is entered when the system cannot generate audio fast enough
	 * to keep up with real-time playback. In this mode, the pause/resume pattern
	 * is bypassed to allow continuous (albeit potentially glitchy) audio output
	 * rather than complete silence.
	 * </p>
	 *
	 * @return {@code true} if in degraded mode, {@code false} otherwise
	 */
	public boolean isDegradedMode() { return degradedMode; }

	/**
	 * Converts a duration to "real time" by applying the buffering rate multiplier.
	 *
	 * @param t the duration in milliseconds
	 * @return the adjusted duration
	 */
	protected long toRealTime(double t) { return (long) (t * rate); }

	/**
	 * Converts a "real time" duration back to actual time by removing the buffering rate multiplier.
	 *
	 * @param t the adjusted duration
	 * @return the actual duration in milliseconds
	 */
	protected long fromRealTime(double t) { return (long) (t / rate); }

	/**
	 * Returns the number of processing cycles that have been completed.
	 *
	 * @return the cycle count
	 */
	public long getRenderedCount() { return count; }

	/**
	 * Returns the total number of audio frames that have been rendered.
	 *
	 * @return the rendered frame count
	 */
	public long getRenderedFrames() { return count * buffer.getDetails().getFrames(); }

	/**
	 * Returns the elapsed real time since the scheduler started, adjusted for pauses.
	 *
	 * @return the adjusted elapsed time in milliseconds
	 */
	public long getRealTime() { return toRealTime(System.currentTimeMillis() - start - totalPaused); }

	/**
	 * Returns the duration of audio that has been rendered, in milliseconds.
	 *
	 * @return the rendered audio duration
	 */
	public long getRenderedTime() { return getRenderedFrames() * 1000 / output.getSampleRate(); }

	/**
	 * Returns the difference between rendered time and real time.
	 * <p>
	 * A positive value indicates rendering is ahead of playback (good),
	 * while a negative value indicates rendering is behind (risk of underrun).
	 * </p>
	 *
	 * @return the rendering gap in milliseconds
	 */
	public long getRenderingGap() { return getRenderedTime() - getRealTime(); }

	/**
	 * Calculates the target sleep duration for the current cycle.
	 *
	 * @return the sleep duration in milliseconds
	 * @see #getTarget(boolean)
	 */
	protected long getTarget() {
		return getTarget(paused);
	}

	/**
	 * Calculates the target sleep duration based on timing state and pause status.
	 * <p>
	 * The calculation considers:
	 * </p>
	 * <ul>
	 *   <li>The timing regularizer's smoothed timing difference</li>
	 *   <li>When not paused: adds half the rendering gap and the timing pad</li>
	 *   <li>When paused: uses 1/4 of the base target for faster resume detection</li>
	 * </ul>
	 *
	 * @param paused whether the scheduler is currently paused
	 * @return the sleep duration in milliseconds (minimum 1ms)
	 */
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

	/**
	 * The main processing loop that runs on the executor thread.
	 * <p>
	 * This loop continuously:
	 * </p>
	 * <ol>
	 *   <li>Checks for auto-resume conditions if paused</li>
	 *   <li>Executes the operations pipeline (input -> process -> output)</li>
	 *   <li>Pauses after completing each buffer group</li>
	 *   <li>Sleeps for an adaptive duration to maintain timing</li>
	 * </ol>
	 * <p>
	 * The loop exits when {@link #stop()} is called.
	 * </p>
	 */
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
					if (!degradedMode) {
						pause();
					}
				}

				target = getTarget();
				lastDuration = System.nanoTime() - s;

				updateDegradedMode(target);

				if (enableVerbose && count % logRate == 0) {
					log("Active cycle " + count +
							" | render=" + NumberFormats.formatNumber(lastDuration / 10e6) + "ms" +
							" | sleep=" + target + "ms" +
							" | gap=" + getRenderingGap() + "ms" +
							" | wp=" + getWritePosition() +
							" | rp=" + output.getReadPosition() +
							(degradedMode ? " | DEGRADED" : ""));
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

	/**
	 * Updates the degraded mode state based on the current sleep target.
	 * <p>
	 * Enters degraded mode when the target falls to or below {@link #DEGRADED_THRESHOLD}.
	 * Exits degraded mode after {@link #RECOVERY_CYCLES} consecutive cycles with
	 * the target above twice the threshold (hysteresis to prevent oscillation).
	 * </p>
	 *
	 * @param target the current sleep target in milliseconds
	 */
	private void updateDegradedMode(long target) {
		if (!degradedMode && target <= DEGRADED_THRESHOLD) {
			degradedMode = true;
			recoveryCount = 0;
			warn("Entering degraded mode - sleep target: " + target + "ms, gap: " + getRenderingGap() + "ms");
		} else if (degradedMode) {
			if (target > DEGRADED_THRESHOLD * 2) {
				recoveryCount++;
				if (recoveryCount >= RECOVERY_CYCLES) {
					degradedMode = false;
					recoveryCount = 0;
					log("Exiting degraded mode - performance restored");
				}
			} else {
				recoveryCount = 0;
			}
		}
	}

	/**
	 * Creates a scheduler with a custom executor and default frame size.
	 *
	 * @param executor the executor service for running the scheduler loop
	 * @param input    the input line, or {@code null} for no input
	 * @param output   the output line
	 * @param source   the audio processing operation
	 * @return a new scheduler instance
	 */
	public static BufferedOutputScheduler create(ExecutorService executor,
												 InputLine input, OutputLine output,
										 	 	 AudioLineOperation source) {
		return create(executor, input, output, output.getBufferSize() / BufferDefaults.batchCount, source);
	}

	/**
	 * Creates a scheduler from a {@link CellList} source with default settings.
	 *
	 * @param input  the input line, or {@code null} for no input
	 * @param output the output line
	 * @param source the cell list providing audio processing
	 * @return a new scheduler instance
	 */
	public static BufferedOutputScheduler create(InputLine input, OutputLine output, CellList source) {
		return create(input, output, source.toLineOperation());
	}

	/**
	 * Creates a scheduler with default executor and frame size.
	 *
	 * @param input  the input line, or {@code null} for no input
	 * @param output the output line
	 * @param source the audio processing operation
	 * @return a new scheduler instance
	 */
	public static BufferedOutputScheduler create(InputLine input, OutputLine output,
												 AudioLineOperation source) {
		return create(input, output,
				output.getBufferSize() / BufferDefaults.batchCount,
				source);
	}

	/**
	 * Creates a scheduler from a {@link CellList} source with a custom frame size.
	 *
	 * @param input  the input line, or {@code null} for no input
	 * @param output the output line
	 * @param frames the number of audio frames per processing cycle
	 * @param source the cell list providing audio processing
	 * @return a new scheduler instance
	 */
	public static BufferedOutputScheduler create(InputLine input, OutputLine output, int frames,
												 CellList source) {
		return create(Executors.newSingleThreadExecutor(),
						input, output, frames,
						source.toLineOperation());
	}

	/**
	 * Creates a scheduler with a custom frame size and default executor.
	 *
	 * @param input  the input line, or {@code null} for no input
	 * @param output the output line
	 * @param frames the number of audio frames per processing cycle
	 * @param source the audio processing operation
	 * @return a new scheduler instance
	 */
	public static BufferedOutputScheduler create(InputLine input, OutputLine output, int frames,
												 AudioLineOperation source) {
		return create(Executors.newSingleThreadExecutor(),
						input, output, frames, source);
	}

	/**
	 * Creates a scheduler with a custom executor and frame size.
	 * <p>
	 * This method creates an {@link AudioBuffer} based on the output line's
	 * sample rate and the specified frame count.
	 * </p>
	 *
	 * @param executor the executor service for running the scheduler loop
	 * @param input    the input line, or {@code null} for no input
	 * @param output   the output line
	 * @param frames   the number of audio frames per processing cycle
	 * @param source   the audio processing operation
	 * @return a new scheduler instance
	 */
	public static BufferedOutputScheduler create(ExecutorService executor,
												 InputLine input, OutputLine output, int frames,
												 AudioLineOperation source) {
		return create(executor, input, output,
				AudioBuffer.create(output.getSampleRate(), frames),
				source);
	}

	/**
	 * Creates a scheduler with full control over all parameters.
	 * <p>
	 * This is the primary factory method that all other {@code create} methods
	 * delegate to. It initializes the scheduler with the provided audio buffer
	 * and converts the audio line operation into a temporal runner.
	 * </p>
	 *
	 * @param executor the executor service for running the scheduler loop
	 * @param input    the input line, or {@code null} for no input
	 * @param output   the output line
	 * @param buffer   the audio buffer for intermediate storage
	 * @param source   the audio processing operation
	 * @return a new scheduler instance
	 */
	public static BufferedOutputScheduler create(ExecutorService executor,
												 InputLine input, OutputLine output,
												 AudioBuffer buffer, AudioLineOperation source) {
		return new BufferedOutputScheduler(
				executor::execute,
				source.process(buffer),
				input, output, buffer);
	}
}
