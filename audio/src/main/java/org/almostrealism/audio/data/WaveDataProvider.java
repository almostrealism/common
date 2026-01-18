/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.data;

import io.almostrealism.relation.Countable;
import org.almostrealism.collect.PackedCollection;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Provider interface for accessing audio waveform data.
 *
 * <p>WaveDataProvider is the primary interface for abstracting audio data sources,
 * whether from files, generated content, or other sources. It provides methods for
 * accessing audio metadata (sample rate, duration, channel count) and the actual
 * audio data with optional sample rate conversion and playback rate adjustment.</p>
 *
 * <h2>Key Methods</h2>
 * <ul>
 *   <li>{@link #get()} - Returns the raw WaveData at the provider's native sample rate</li>
 *   <li>{@link #get(int)} - Returns WaveData resampled to a specific sample rate</li>
 *   <li>{@link #getChannelData(int, double)} - Returns a single channel with playback rate scaling</li>
 * </ul>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link FileWaveDataProvider} - Loads from WAV files</li>
 *   <li>{@link DelegateWaveDataProvider} - Extracts a slice of another provider</li>
 *   <li>{@link SupplierWaveDataProvider} - Wraps a supplier function</li>
 *   <li>{@link DynamicWaveDataProvider} - Dynamic/generated content</li>
 * </ul>
 *
 * @see WaveData
 * @see AudioDataProvider
 */
public interface WaveDataProvider extends AudioDataProvider, Supplier<WaveData>, Countable, Comparable<WaveDataProvider> {

	String getKey();

	default int getCount(double playbackRate, int sampleRate) {
		if (getSampleRate() == sampleRate) {
			return getCount(playbackRate);
		}

		return getCount(playbackRate * getSampleRate() / (double) sampleRate);
	}

	int getCount(double playbackRate);

	double getDuration();

	double getDuration(double playbackRate);

	int getChannelCount();

	default WaveData get(int sampleRate) {
		if (getSampleRate() == sampleRate) {
			return get();
		} else if (getChannelCount() == 1) {
			return new WaveData(getChannelData(0, 1.0, sampleRate), sampleRate);
		}

		int frames = Math.toIntExact(getCountLong() * sampleRate / getSampleRate());
		WaveData result = new WaveData(getChannelCount(), frames, sampleRate);

		for (int i = 0; i < getChannelCount(); i++) {
			result.getData().setMem(i * frames, getChannelData(i, 1.0, sampleRate));
		}

		return result;
	}

	default PackedCollection getChannelData(int channel, double playbackRate, int sampleRate) {
		if (getSampleRate() == sampleRate) {
			return getChannelData(channel, playbackRate);
		}

		return getChannelData(channel, playbackRate * getSampleRate() / (double) sampleRate);
	}

	PackedCollection getChannelData(int channel, double playbackRate);

	@Override
	default int compareTo(WaveDataProvider o) {
		return Optional.ofNullable(getIdentifier()).orElse("").compareTo(
				Optional.ofNullable(o.getIdentifier()).orElse(""));
	}
}
