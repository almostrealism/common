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

package org.almostrealism.audio.persistence;

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

public class WaveDetailsOutputLine implements OutputLine, CodeFeatures, ConsoleFeatures {
	private final int sampleRate;
	private boolean altBuffer;

	private int cursor;
	private final int batchCount;
	private final int framesPerBatch;
	private PackedCollection bufferA;
	private PackedCollection bufferB;

	private final boolean[] silence;
	private BooleanSupplier silenceDetector;

	private ExecutorService executor;
	private final Consumer<Audio.WaveDetailData> consumer;
	private boolean active;

	public WaveDetailsOutputLine(AudioLibraryDataWriter writer) {
		this(writer::bufferData);
	}

	public WaveDetailsOutputLine(Consumer<Audio.WaveDetailData> consumer) {
		this(BufferedAudio.sampleRate, consumer);
	}

	public WaveDetailsOutputLine(int sampleRate, Consumer<Audio.WaveDetailData> consumer) {
		this(sampleRate, 16, BufferDefaults.batchSize, consumer);
	}

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

	public boolean isActive() { return active; }
	public void setActive(boolean active) { this.active = active; }

	public BooleanSupplier getSilenceDetector() { return silenceDetector; }
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

	protected PackedCollection getRecordingBuffer() {
		return altBuffer ? bufferB : bufferA;
	}

	protected PackedCollection getPublishingBuffer() {
		return altBuffer ? bufferA : bufferB;
	}

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
