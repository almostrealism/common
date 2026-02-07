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

import org.almostrealism.audio.api.Audio;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.KeyUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AudioLibraryDataWriter implements ConsoleFeatures {
	/**
	 * Number of {@link Audio.WaveDetailData}s to be buffered before
	 * being grouped together as a {@link Audio.WaveRecording} and
	 * queued for writing to the library.
	 */
	public static final int RECORD_SIZE = 64;

	/**
	 * Number of {@link Audio.WaveRecording}s to be queued
	 * before being actually written to disk.
	 */
	public static final int WRITE_SIZE = 32;

	private String groupKey;
	private final LibraryDestination destination;
	private final ExecutorService executor;

	private String sampleKey;
	private List<Audio.WaveDetailData> buffer;
	private int sampleCount;
	private Consumer<String> sampleListener;
	private Supplier<String> groupKeyProvider;

	private final BlockingQueue<Audio.WaveRecording> queue;
	private int groupCount;
	private int groupTotalFrames;
	private int groupFrameLimit;

	public AudioLibraryDataWriter(LibraryDestination destination) {
		this.destination = destination;
		this.executor = Executors.newSingleThreadExecutor();
		this.queue = new ArrayBlockingQueue<>(WRITE_SIZE * 2);
	}

	public AudioLibraryDataWriter(String groupKey, String prefix) {
		this(new LibraryDestination(prefix));
		restart(groupKey);
	}

	public Consumer<String> getSampleListener() { return sampleListener; }
	public void setSampleListener(Consumer<String> sampleListener) {
		this.sampleListener = sampleListener;
	}

	public Supplier<String> getGroupKeyProvider() { return groupKeyProvider; }
	public void setGroupKeyProvider(Supplier<String> groupKeyProvider) {
		this.groupKeyProvider = groupKeyProvider;
	}

	public int getGroupFrameLimit() { return groupFrameLimit; }
	public void setGroupFrameLimit(int groupFrameLimit) {
		this.groupFrameLimit = groupFrameLimit;
	}

	public boolean isGroupLimitExceeded() {
		return groupFrameLimit > 0 && groupTotalFrames >= groupFrameLimit;
	}

	public String start() { return start(KeyUtils.generateKey()); }

	public String start(String groupKey) {
		if (this.groupKey != null) {
			throw new IllegalArgumentException();
		}

		this.groupKey = groupKey;
		return groupKey;
	}

	public void reset() {
		reset(true);
	}

	/**
	 * Reset the writer, optionally flushing any buffered data.
	 *
	 * @param flush  If true, buffered data will be flushed. This will
	 *              allow all data to be properly written to the library.
	 *              Setting this to false preserves the buffered data,
	 *              which will end up written to the next group. This
	 *              is useful for switching between groups instantly,
	 *              but otherwise it will result in old sample being
	 *              written to a later, unrelated group.
	 */
	protected void reset(boolean flush) {
		if (flush) {
			flushBuffer();
			flushQueue();
			this.buffer = null;
		} else if (sampleKey != null) {
			warn("Resetting without ending active sample");
		}

		this.groupKey = null;
		this.sampleKey = null;
		this.groupCount = 0;
		this.groupTotalFrames = 0;
		this.sampleCount = 0;
	}

	/**
	 * Restart with a new key. Note that some data in the buffer
	 * may end up written to the new group when using this method,
	 * unlike when directly starting and resetting group writing
	 * with {@link #reset()} and {@link #start()}.
	 */
	public String restart(String key) {
		log("Restarting with key " + key);
		reset(false);
		return start(key);
	}

	public void bufferData(Audio.WaveDetailData data) {
		if (buffer == null || buffer.size() >= RECORD_SIZE) {
			flushBuffer();
		}

		if (data.getSilent()) {
			// Sample is over when silence is detected
			endSample();
		} else if (sampleKey == null) {
			// Start a new sample when sound is detected,
			// if there is not already a sample in progress
			startSample();
		}

		buffer.add(data);
	}

	public void startSample() {
		if (sampleKey != null) {
			throw new UnsupportedOperationException();
		} else if (isGroupLimitExceeded()) {
			// Do not allow any new samples to begin if the
			// group limit is already exceeded
			return;
		}

		flushBuffer();
		sampleKey = KeyUtils.generateKey();
	}

	public void endSample() {
		if (sampleKey != null) {
			flushBuffer();
			sampleListener.accept(sampleKey);
		}

		sampleKey = null;
		sampleCount = 0;
	}

	protected void flushBuffer() {
		if (buffer == null) {
			buffer = new ArrayList<>();
		} else if (!buffer.isEmpty()) {
			queueRecording(buffer);
			buffer = new ArrayList<>();
		}
	}

	protected void queueRecording(List<Audio.WaveDetailData> buffer) {
		Audio.WaveRecording.Builder r = Audio.WaveRecording.newBuilder()
				.setGroupKey(groupKey).setGroupOrderIndex(groupCount++)
				.addAllData(buffer);
		if (sampleKey != null) {
			r.setKey(sampleKey).setOrderIndex(sampleCount++);
			r.setSilent(false);
		} else {
			r.setKey(KeyUtils.generateKey());
			r.setSilent(true);
		}

		queueRecording(r.build());
	}

	private void queueRecording(Audio.WaveRecording recording) {
		groupTotalFrames += recording.getDataList().stream()
				.mapToInt(Audio.WaveDetailData::getFrameCount)
				.sum();

		queue.add(recording);

		if (queue.size() >= WRITE_SIZE) {
			flushQueue();
		}

		checkGroupLimit();
	}

	private String checkGroupLimit() {
		if (!isGroupLimitExceeded()) {
			return null;
		}

		// If there is a currently active sample, allow a grace period
		// for the sample to end before the group is ended
		double gracePeriod = sampleKey == null ? 1.0 : 1.3;
		int limit = (int) (groupFrameLimit * gracePeriod);

		if (sampleKey != null && groupTotalFrames >= limit) {
			// If the grace period is exceeded, end the sample. No new
			// samples will be allowed to start and the next queued
			// recording will deal with properly ending the group
			log("Ending sample due to group limit");
			endSample();
			return null;
		} else if (getGroupKeyProvider() == null) {
			throw new UnsupportedOperationException();
		}

		return restart(getGroupKeyProvider().get());
	}

	protected void flushQueue() {
		List<Audio.WaveRecording> recordings = new ArrayList<>();
		queue.drainTo(recordings);
		executor.submit(() -> {
			try (LibraryDestination.Writer writer = destination.out()) {
				AudioLibraryPersistence.saveRecordings(recordings, writer);
//				totalData += recordings.stream()
//						.mapToInt(Audio.WaveRecording::getDataCount)
//						.sum();
//				log("Saved " + totalData + " recording chunks so far (" +
//						Arrays.toString(recordings.stream()
//								.map(Audio.WaveRecording::getKey)
//								.toArray()) + ")");
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}
}
