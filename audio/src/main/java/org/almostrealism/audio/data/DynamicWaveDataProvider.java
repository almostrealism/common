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

import io.almostrealism.cycle.Setup;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class DynamicWaveDataProvider extends WaveDataProviderAdapter implements Setup {
	private final String key;
	private final WaveData destination;
	private final Supplier<Runnable> setup;

	public DynamicWaveDataProvider(String key, WaveData destination) {
		this(key, destination, new OperationList());
	}

	public DynamicWaveDataProvider(String key, WaveData destination, Supplier<Runnable> setup) {
		this.key = key;
		this.destination = destination;
		this.setup = setup;
	}

	@Override
	public String getKey() { return key; }

	@Override
	public String getIdentifier() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getCountLong() {
		return destination.getFrameCount();
	}

	@Override
	public double getDuration() {
		return getCount() / (double) getSampleRate();
	}

	@Override
	public int getChannelCount() { return 1; }

	@Override
	public int getSampleRate() {
		return destination.getSampleRate();
	}

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	protected WaveData load() { return destination; }
}
