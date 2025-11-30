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

import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

public class SupplierWaveDataProvider extends WaveDataProviderAdapter {
	private final String key;
	private final Supplier<PackedCollection> source;
	private final int sampleRate;

	public SupplierWaveDataProvider(String key, Supplier<PackedCollection> source, int sampleRate) {
		this.key = key;
		this.source = source;
		this.sampleRate = sampleRate;
	}

	@Override
	public long getCountLong() {
		return get().getFrameCount();
	}

	@Override
	public double getDuration() {
		return get().getDuration();
	}

	@Override
	public int getChannelCount() { return get().getChannelCount(); }

	@Override
	public int getSampleRate() {
		return sampleRate;
	}

	@Override
	public String getKey() {
		if (key == null) {
			throw new NullPointerException();
		}

		return key;
	}

	@Override
	public String getIdentifier() {
		throw new UnsupportedOperationException();
	}

	@Override
	protected WaveData load() {
		return new WaveData(source.get(), sampleRate);
	}
}
