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
	public static final int groups = 4;
	public static final int batchesPerGroup = 2;
	public static final int batchCount = groups * batchesPerGroup;

	public static int readGroupSensitivityPadding = 1024;
	public static int batchSize = 8 * 1024;
	public static double bufferingRate = 4.0;

	public static int defaultBufferSize = batchSize * batchCount;

	public static int padReadPosition(int readPosition, int bufferSize) {
		int rp = readPosition + readGroupSensitivityPadding;
		if (rp > bufferSize) rp = rp - bufferSize;
		return rp;
	}

	public static boolean isSafeGroup(int writePosition, int readPosition, int groupSize, int bufferSize) {
		int activeGroup = writePosition / groupSize;
		int sensitiveGroup = padReadPosition(readPosition, bufferSize) / groupSize;
		return activeGroup != sensitiveGroup;
	}

	public static double getGroupDuration(int sampleRate, int totalFrames) {
		int framesPerGroup = totalFrames / groups;
		return framesPerGroup / (double) sampleRate;
	}

	public static void logBufferInfo(int sampleRate, int totalFrames, Consumer<String> out) {
		out.accept("Buffer duration is " +
				NumberFormats.formatNumber(totalFrames / (double) sampleRate) + "s");
		out.accept("Group duration is " +
				NumberFormats.formatNumber(getGroupDuration(sampleRate, totalFrames)) + "s");
	}
}
