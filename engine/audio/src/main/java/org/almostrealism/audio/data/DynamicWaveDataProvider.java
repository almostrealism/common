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

import io.almostrealism.lifecycle.Setup;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A wave data provider backed by dynamically generated or processed audio data.
 *
 * <p>DynamicWaveDataProvider wraps a {@link WaveData} instance that may be
 * populated or modified at runtime, with an optional setup operation that
 * runs before the data is accessed. This is useful for synthesized audio
 * or audio that requires preprocessing.</p>
 *
 * <p>A metadata-only instance (created via {@link #DynamicWaveDataProvider(String, int)})
 * carries only the identifier and sample rate, returning null from {@link #load()}.
 * This is used when an entry has frequency data but no audio waveform — the
 * {@link WaveDetailsFactory} will synthesize audio from the frequency data
 * during feature computation.</p>
 *
 * @see WaveDataProvider
 * @see WaveData
 */
public class DynamicWaveDataProvider extends WaveDataProviderAdapter implements Setup {
	/** Unique identifier for this provider. */
	private final String identifier;

	/** The destination WaveData instance that holds the audio. */
	private final WaveData destination;

	/** Sample rate, stored separately so metadata-only providers can report it. */
	private final int sampleRate;

	/** Optional setup operations to run before the audio data is accessed. */
	private final Supplier<Runnable> setup;

	/**
	 * Creates a metadata-only provider that carries an identifier and sample
	 * rate but has no audio data. {@link #load()} returns null.
	 *
	 * @param identifier the content identifier
	 * @param sampleRate the sample rate for this entry
	 */
	public DynamicWaveDataProvider(String identifier, int sampleRate) {
		this.identifier = identifier;
		this.destination = null;
		this.sampleRate = sampleRate;
		this.setup = new OperationList();
	}

	/**
	 * Creates a DynamicWaveDataProvider with no additional setup operations.
	 *
	 * @param identifier unique identifier for this provider
	 * @param destination the WaveData holding the audio content
	 */
	public DynamicWaveDataProvider(String identifier, WaveData destination) {
		this(identifier, destination, new OperationList());
	}

	/**
	 * Creates a DynamicWaveDataProvider with the given setup supplier.
	 *
	 * @param identifier  unique identifier for this provider
	 * @param destination the WaveData holding the audio content
	 * @param setup       setup operations to run before the data is accessed
	 */
	public DynamicWaveDataProvider(String identifier, WaveData destination, Supplier<Runnable> setup) {
		this.identifier = identifier;
		this.destination = destination;
		this.sampleRate = destination != null ? destination.getSampleRate() : 0;
		this.setup = setup;
	}

	@Override
	public String getKey() { return identifier; }

	@Override
	public String getIdentifier() { return identifier; }

	@Override
	public long getCountLong() { return destination != null ? destination.getFrameCount() : 0; }

	@Override
	public double getDuration() {
		return getSampleRate() > 0 ? getCount() / (double) getSampleRate() : 0;
	}

	@Override
	public int getChannelCount() { return destination != null ? destination.getChannelCount() : 1; }

	@Override
	public int getSampleRate() { return sampleRate; }

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	protected WaveData load() { return destination; }
}
