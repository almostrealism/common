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

/**
 * Buffers incoming {@link Audio.WaveDetailData} entries and asynchronously
 * writes them to a {@link LibraryDestination} grouped as {@link Audio.WaveRecording}s.
 * Audio is gathered into samples (sequences of non-silent batches) and into
 * groups (collections of samples) keyed by a group key.
 */
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

	/** The active group key used to tag all recordings in the current group. */
	private String groupKey;

	/** The library destination to which recordings are written. */
	private final LibraryDestination destination;

	/** Executor that performs writes off the audio thread. */
	private final ExecutorService executor;

	/** Key for the current in-progress sample, or {@code null} if no sample is active. */
	private String sampleKey;

	/** Buffer accumulating wave detail data for the current recording chunk. */
	private List<Audio.WaveDetailData> buffer;

	/** Number of recording chunks completed in the current sample. */
	private int sampleCount;

	/** Consumer notified with the sample key when a sample ends. */
	private Consumer<String> sampleListener;

	/** Supplier invoked to obtain a new group key when the current group limit is exceeded. */
	private Supplier<String> groupKeyProvider;

	/** Queue holding completed recordings waiting to be written to disk. */
	private final BlockingQueue<Audio.WaveRecording> queue;

	/** Number of recording chunks queued in the current group. */
	private int groupCount;

	/** Total number of audio frames queued in the current group. */
	private int groupTotalFrames;

	/** Maximum number of frames allowed per group; 0 means unlimited. */
	private int groupFrameLimit;

	/**
	 * Creates a writer that persists recordings to the given destination.
	 *
	 * @param destination the library destination for storage
	 */
	public AudioLibraryDataWriter(LibraryDestination destination) {
		this.destination = destination;
		this.executor = Executors.newSingleThreadExecutor();
		this.queue = new ArrayBlockingQueue<>(WRITE_SIZE * 2);
	}

	/**
	 * Creates a writer that persists recordings to a {@link LibraryDestination} at the given
	 * prefix and immediately starts a new group with the given key.
	 *
	 * @param groupKey the initial group key
	 * @param prefix   the destination path prefix
	 */
	public AudioLibraryDataWriter(String groupKey, String prefix) {
		this(new LibraryDestination(prefix));
		restart(groupKey);
	}

	/** Returns the consumer notified with each completed sample key. */
	public Consumer<String> getSampleListener() { return sampleListener; }

	/**
	 * Sets the consumer notified with each completed sample key.
	 *
	 * @param sampleListener the consumer to notify
	 */
	public void setSampleListener(Consumer<String> sampleListener) {
		this.sampleListener = sampleListener;
	}

	/** Returns the supplier invoked to obtain a new group key when the current group limit is exceeded. */
	public Supplier<String> getGroupKeyProvider() { return groupKeyProvider; }

	/**
	 * Sets the supplier invoked to obtain a new group key when the group frame limit is exceeded.
	 *
	 * @param groupKeyProvider supplier of new group keys
	 */
	public void setGroupKeyProvider(Supplier<String> groupKeyProvider) {
		this.groupKeyProvider = groupKeyProvider;
	}

	/** Returns the maximum number of audio frames allowed per group (0 means unlimited). */
	public int getGroupFrameLimit() { return groupFrameLimit; }

	/**
	 * Sets the maximum number of audio frames allowed per group.
	 * When exceeded, the group is restarted via the group key provider.
	 *
	 * @param groupFrameLimit the frame limit, or 0 for unlimited
	 */
	public void setGroupFrameLimit(int groupFrameLimit) {
		this.groupFrameLimit = groupFrameLimit;
	}

	/**
	 * Returns {@code true} if the total frames queued in the current group has reached
	 * or exceeded the configured frame limit.
	 *
	 * @return {@code true} if the group limit is exceeded
	 */
	public boolean isGroupLimitExceeded() {
		return groupFrameLimit > 0 && groupTotalFrames >= groupFrameLimit;
	}

	/**
	 * Starts a new recording group with a randomly generated key.
	 *
	 * @return the generated group key
	 */
	public String start() { return start(KeyUtils.generateKey()); }

	/**
	 * Starts a new recording group with the given key.
	 *
	 * @param groupKey the key to identify this group
	 * @return the group key
	 * @throws IllegalArgumentException if a group is already active
	 */
	public String start(String groupKey) {
		if (this.groupKey != null) {
			throw new IllegalArgumentException();
		}

		this.groupKey = groupKey;
		return groupKey;
	}

	/**
	 * Resets the writer, flushing any buffered data to disk and clearing the active group.
	 */
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

	/**
	 * Buffers a single wave detail data entry, flushing and starting/ending samples
	 * based on silence detection.
	 *
	 * @param data the wave detail data to buffer
	 */
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

	/**
	 * Starts a new sample within the current group, flushing any buffered data first.
	 * Does nothing if the group frame limit is already exceeded.
	 *
	 * @throws UnsupportedOperationException if a sample is already in progress
	 */
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

	/**
	 * Ends the current sample, flushing any buffered data and notifying the sample listener.
	 */
	public void endSample() {
		if (sampleKey != null) {
			flushBuffer();
			sampleListener.accept(sampleKey);
		}

		sampleKey = null;
		sampleCount = 0;
	}

	/**
	 * Flushes the current buffer by queuing it as a recording if non-empty,
	 * and initializes a new empty buffer.
	 */
	protected void flushBuffer() {
		if (buffer == null) {
			buffer = new ArrayList<>();
		} else if (!buffer.isEmpty()) {
			queueRecording(buffer);
			buffer = new ArrayList<>();
		}
	}

	/**
	 * Builds a {@link Audio.WaveRecording} from the given buffer and queues it for writing.
	 *
	 * @param buffer the list of wave detail data to package into a recording
	 */
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

	/**
	 * Adds a completed recording to the write queue and flushes when the queue
	 * reaches {@link #WRITE_SIZE}. Also checks whether the current group frame
	 * limit has been exceeded.
	 *
	 * @param recording the protobuf recording to enqueue
	 */
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

	/**
	 * Checks whether the current group frame limit has been exceeded and, if so,
	 * begins a new group, allowing a grace period for any active sample to finish.
	 *
	 * @return the new group key if a group transition occurred, or {@code null} otherwise
	 */
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

	/**
	 * Drains all queued recordings and submits a write task to the executor.
	 */
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
				warn(e.getMessage(), e);
			}
		});
	}
}
