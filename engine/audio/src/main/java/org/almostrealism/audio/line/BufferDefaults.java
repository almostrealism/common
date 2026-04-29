/*
 * Copyright 2024 Michael Murray
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

import java.util.function.Consumer;

/**
 * Provides default buffer configuration and utilities for real-time audio processing.
 * This class defines buffer sizes, group-based safety checking, and timing calculations
 * used by {@link BufferedOutputScheduler} to prevent buffer underruns and overruns.
 * <p>
 * The buffer is divided into groups, with write operations pausing after completing
 * each group to ensure the read position has advanced sufficiently before continuing.
 * This prevents overwriting audio data that hasn't been consumed yet.
 * </p>
 *
 * @see BufferedOutputScheduler
 */
public class BufferDefaults {
	/** Number of buffer groups used to segment the circular output buffer. */
	public static final int groups = 4;

	/** Number of processing batches per buffer group. */
	public static final int batchesPerGroup = 2;

	/** Total number of processing batches across all groups (groups * batchesPerGroup). */
	public static final int batchCount = groups * batchesPerGroup;

	/** Number of additional frames added to the read position before group-boundary checks, to avoid false-safe readings. */
	public static int readGroupSensitivityPadding = 1024;

	/** Number of frames in a single processing batch. */
	public static int batchSize = 8 * 1024;

	/** Rate multiplier for real-time timing calculations; higher values target faster-than-real-time rendering. */
	public static double bufferingRate = 4.0;

	/** Default output buffer size in frames (batchSize * batchCount). */
	public static int defaultBufferSize = batchSize * batchCount;

	/**
	 * Advances the read position by the sensitivity padding amount, wrapping within the buffer.
	 *
	 * @param readPosition current hardware read position in frames
	 * @param bufferSize   total circular buffer size in frames
	 * @return the padded read position
	 */
	public static int padReadPosition(int readPosition, int bufferSize) {
		int rp = readPosition + readGroupSensitivityPadding;
		if (rp > bufferSize) rp = rp - bufferSize;
		return rp;
	}

	/**
	 * Returns true if the write position is in a different group than the padded read position,
	 * indicating it is safe to continue writing without overwriting unread data.
	 *
	 * @param writePosition current write position in frames
	 * @param readPosition  current hardware read position in frames
	 * @param groupSize     number of frames per group
	 * @param bufferSize    total circular buffer size in frames
	 * @return true if the write position is in a safe group
	 */
	public static boolean isSafeGroup(int writePosition, int readPosition, int groupSize, int bufferSize) {
		int activeGroup = writePosition / groupSize;
		int sensitiveGroup = padReadPosition(readPosition, bufferSize) / groupSize;
		return activeGroup != sensitiveGroup;
	}

	/**
	 * Returns the duration of a single buffer group in seconds.
	 *
	 * @param sampleRate  audio sample rate in Hz
	 * @param totalFrames total number of frames in the buffer
	 * @return duration of one group in seconds
	 */
	public static double getGroupDuration(int sampleRate, int totalFrames) {
		int framesPerGroup = totalFrames / groups;
		return framesPerGroup / (double) sampleRate;
	}

	/**
	 * Logs buffer size and group duration information using the provided output consumer.
	 *
	 * @param sampleRate  audio sample rate in Hz
	 * @param totalFrames total number of frames in the buffer
	 * @param out         consumer that receives formatted log lines
	 */
	public static void logBufferInfo(int sampleRate, int totalFrames, Consumer<String> out) {
		out.accept("Buffer duration is " +
				NumberFormats.formatNumber(totalFrames / (double) sampleRate) + "s");
		out.accept("Group duration is " +
				NumberFormats.formatNumber(getGroupDuration(sampleRate, totalFrames)) + "s");
	}
}
