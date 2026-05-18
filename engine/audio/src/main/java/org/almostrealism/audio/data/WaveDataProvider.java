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
 * <h2>Key vs Identifier</h2>
 * <p>WaveDataProvider uses two different identifiers for different purposes:</p>
 * <ul>
 *   <li><b>{@link #getKey()}</b> - The <em>location</em> of the audio data.
 *       For {@link FileWaveDataProvider}, this is the file path.
 *       This is what you use to find or display the audio source.</li>
 *   <li><b>{@link AudioDataProvider#getIdentifier()}</b> - The <em>content identifier</em>.
 *       For {@link FileWaveDataProvider}, this is an MD5 hash of the file contents.
 *       This is used for content-based deduplication and matching.</li>
 * </ul>
 *
 * <h3>Example: Resolving a file path</h3>
 * <pre>{@code
 * // Given a WaveDetails with only an identifier (e.g., from protobuf)
 * WaveDetails details = ...;
 * String identifier = details.getIdentifier();  // MD5 hash
 *
 * // Find the provider in the library's file tree
 * WaveDataProvider provider = library.find(identifier);
 *
 * // Get the actual file path
 * String filePath = provider.getKey();  // e.g., "/path/to/samples/kick.wav"
 * }</pre>
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
 * @see org.almostrealism.audio.AudioLibrary#find(String)
 */
public interface WaveDataProvider extends AudioDataProvider, Supplier<WaveData>, Countable, Comparable<WaveDataProvider> {

	/**
	 * Returns the location key for this audio data.
	 *
	 * <p>For file-based providers, this is the file path. This is distinct from
	 * {@link AudioDataProvider#getIdentifier()} which returns a content-based
	 * identifier (MD5 hash for files).</p>
	 *
	 * <p>Use this method to get the displayable/resolvable path to the audio source.</p>
	 *
	 * @return the location key (file path for file-based providers)
	 * @see AudioDataProvider#getIdentifier()
	 */
	String getKey();

	/**
	 * Returns the number of frames for the given playback rate and target sample rate.
	 * If the provider's sample rate matches {@code sampleRate}, delegates to
	 * {@link #getCount(double)}; otherwise adjusts the playback rate to account for resampling.
	 *
	 * @param playbackRate relative playback rate (1.0 = normal speed)
	 * @param sampleRate   target sample rate in Hz
	 * @return frame count at the given playback rate and target sample rate
	 */
	default int getCount(double playbackRate, int sampleRate) {
		if (getSampleRate() == sampleRate) {
			return getCount(playbackRate);
		}

		return getCount(playbackRate * getSampleRate() / (double) sampleRate);
	}

	/**
	 * Returns the number of frames for the given playback rate at the provider's native sample rate.
	 *
	 * @param playbackRate relative playback rate (1.0 = normal speed)
	 * @return frame count at the given playback rate
	 */
	int getCount(double playbackRate);

	/**
	 * Returns the native duration of the audio in seconds at normal (1.0) playback rate.
	 *
	 * @return duration in seconds
	 */
	double getDuration();

	/**
	 * Returns the duration of the audio in seconds at the given playback rate.
	 *
	 * @param playbackRate relative playback rate (1.0 = normal speed)
	 * @return duration in seconds at the given rate
	 */
	double getDuration(double playbackRate);

	/**
	 * Returns the number of audio channels provided (e.g., 1 for mono, 2 for stereo).
	 *
	 * @return channel count
	 */
	int getChannelCount();

	/**
	 * Returns audio data resampled to the target sample rate if necessary.
	 *
	 * @param sampleRate desired output sample rate in Hz
	 * @return WaveData at the given sample rate
	 */
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

	/**
	 * Returns raw PCM data for the specified channel, resampled to the given sample rate if necessary.
	 *
	 * @param channel      channel index (0-based)
	 * @param playbackRate relative playback rate (1.0 = normal speed)
	 * @param sampleRate   desired output sample rate in Hz
	 * @return channel audio data at the given playback rate and sample rate
	 */
	default PackedCollection getChannelData(int channel, double playbackRate, int sampleRate) {
		if (getSampleRate() == sampleRate) {
			return getChannelData(channel, playbackRate);
		}

		return getChannelData(channel, playbackRate * getSampleRate() / (double) sampleRate);
	}

	/**
	 * Returns raw PCM data for the specified channel at the given playback rate
	 * at the provider's native sample rate.
	 *
	 * @param channel      channel index (0-based)
	 * @param playbackRate relative playback rate (1.0 = normal speed)
	 * @return channel audio data
	 */
	PackedCollection getChannelData(int channel, double playbackRate);

	@Override
	default int compareTo(WaveDataProvider o) {
		return Optional.ofNullable(getIdentifier()).orElse("").compareTo(
				Optional.ofNullable(o.getIdentifier()).orElse(""));
	}
}
