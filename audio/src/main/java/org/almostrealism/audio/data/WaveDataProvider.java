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
