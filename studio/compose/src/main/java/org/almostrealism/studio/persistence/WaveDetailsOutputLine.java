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

package org.almostrealism.studio.persistence;

import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.BufferedAudio;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Output line that buffers incoming audio samples and asynchronously publishes
 * {@link Audio.WaveDetailData} messages via a consumer for persistent storage or
 * streaming.
 */
public class WaveDetailsOutputLine implements OutputLine, CodeFeatures, ConsoleFeatures {
	/** Audio sample rate for this output line. */
	private final int sampleRate;

	/** Indicates which double-buffer slot is currently being written to. */
	private boolean altBuffer;

	/** Write cursor within the active recording buffer (in samples). */
	private int cursor;

	/** Number of batches per full recording buffer. */
	private final int batchCount;

	/** Number of audio frames per batch. */
	private final int framesPerBatch;

	/** Primary recording / publishing buffer. */
	private PackedCollection bufferA;

	/** Secondary recording / publishing buffer. */
	private PackedCollection bufferB;

	/** Per-batch silence flags, set when a batch is detected as silent. */
	private final boolean[] silence;

	/** Supplier that reports whether the current frame is silent. */
	private BooleanSupplier silenceDetector;

	/** Executor used to submit wave-detail publish tasks off the audio thread. */
	private ExecutorService executor;

	/** Consumer that receives completed wave detail messages. */
	private final Consumer<Audio.WaveDetailData> consumer;

	/** When {@code false}, recording is suppressed and {@link #publish()} does nothing. */
	private boolean active;

	/**
	 * Creates an output line that publishes data to the given writer.
	 *
	 * @param writer the library data writer to receive wave detail data
	 */
	public WaveDetailsOutputLine(AudioLibraryDataWriter writer) {
		this(writer::bufferData);
	}

	/**
	 * Creates an output line with the default sample rate and batch configuration.
	 *
	 * @param consumer the consumer for completed wave detail messages
	 */
	public WaveDetailsOutputLine(Consumer<Audio.WaveDetailData> consumer) {
		this(BufferedAudio.sampleRate, consumer);
	}

	/**
	 * Creates an output line with the given sample rate and default batch configuration.
	 *
	 * @param sampleRate the audio sample rate
	 * @param consumer   the consumer for completed wave detail messages
	 */
	public WaveDetailsOutputLine(int sampleRate, Consumer<Audio.WaveDetailData> consumer) {
		this(sampleRate, 16, BufferDefaults.batchSize, consumer);
	}

	/**
	 * Creates an output line with full configuration.
	 *
	 * @param sampleRate    the audio sample rate
	 * @param batchCount    the number of batches per recording buffer
	 * @param framesPerBatch the number of audio frames per batch
	 * @param consumer      the consumer for completed wave detail messages
	 */
	public WaveDetailsOutputLine(int sampleRate, int batchCount, int framesPerBatch,
								 Consumer<Audio.WaveDetailData> consumer) {
		this.sampleRate = sampleRate;
		this.batchCount = batchCount;
		this.framesPerBatch = framesPerBatch;
		this.bufferA = new PackedCollection(batchCount * framesPerBatch);
		this.bufferB = new PackedCollection(batchCount * framesPerBatch);
		this.silence = new boolean[batchCount];

		this.executor = Executors.newSingleThreadExecutor();
		this.consumer = consumer;
	}

	@Override
	public int getSampleRate() { return sampleRate; }

	@Override
	public int getBufferSize() { return bufferA.getMemLength(); }

	/** Returns {@code true} if this output line is currently active. */
	public boolean isActive() { return active; }

	/**
	 * Enables or disables recording; when {@code false}, calls to {@link #publish()} are no-ops.
	 *
	 * @param active {@code true} to enable recording
	 */
	public void setActive(boolean active) { this.active = active; }

	/** Returns the silence detector supplier, or {@code null} if not configured. */
	public BooleanSupplier getSilenceDetector() { return silenceDetector; }

	/**
	 * Sets the silence detector supplier used to flag silent batches.
	 *
	 * @param silenceDetector supplier returning {@code true} when the current frame is silent
	 */
	public void setSilenceDetector(BooleanSupplier silenceDetector) {
		this.silenceDetector = silenceDetector;
	}

	@Override
	public void write(PackedCollection sample) {
		PackedCollection output = getRecordingBuffer();

		if (sample.getMemLength() > output.getMemLength() - cursor || sample.getMemLength() != framesPerBatch) {
			throw new IllegalArgumentException();
		}

		output.setMem(cursor, sample);
		silence[cursor / framesPerBatch] = silenceDetector != null && silenceDetector.getAsBoolean();
		cursor = (cursor + sample.getMemLength()) % output.getMemLength();

		if (cursor == 0) {
			altBuffer = !altBuffer;
			publish();
		}
	}

	/** Returns the currently active recording buffer. */
	protected PackedCollection getRecordingBuffer() {
		return altBuffer ? bufferB : bufferA;
	}

	/** Returns the previously completed buffer ready for publishing. */
	protected PackedCollection getPublishingBuffer() {
		return altBuffer ? bufferA : bufferB;
	}

	/**
	 * Publishes the completed publishing buffer as wave detail messages to the consumer.
	 * Does nothing if the line is not active.
	 */
	protected void publish() {
		if (!isActive()) return;

		List<Audio.WaveDetailData> data = new ArrayList<>();

		for (int batch = 0; batch < batchCount; batch++) {
			WaveDetails details = new WaveDetails();
			details.setSampleRate(getSampleRate());
			details.setChannelCount(1);
			details.setFrameCount(framesPerBatch);
			details.setData(getPublishingBuffer().range(shape(framesPerBatch), batch * framesPerBatch));
			details.setSilent(silence[batch]);

			// TODO  Do not store audio for silent batches?
			data.add(AudioLibraryPersistence.encode(details, true));
		}

		executor.submit(() -> data.forEach(consumer));
	}

	@Override
	public void destroy() {
		bufferA.destroy();
		bufferB.destroy();
		executor.shutdown();
		bufferA = null;
		bufferB = null;
		executor = null;
	}
}
