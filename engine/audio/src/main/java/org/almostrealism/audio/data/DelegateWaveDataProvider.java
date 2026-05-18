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

/**
 * A wave data provider that extracts a portion of another provider's data.
 *
 * <p>DelegateWaveDataProvider wraps an existing {@link WaveDataProvider} and
 * provides access to a specific range of frames, useful for slicing audio
 * samples or extracting segments from longer recordings.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Extract 44100 frames starting at frame 88200 (seconds 2-3 at 44.1kHz)
 * WaveDataProvider original = new FileWaveDataProvider("long_sample.wav");
 * WaveDataProvider slice = new DelegateWaveDataProvider(original, 88200, 44100);
 * }</pre>
 *
 * @see WaveDataProvider
 * @see WaveDataProviderAdapter
 */
public class DelegateWaveDataProvider extends WaveDataProviderAdapter {
	/** The underlying provider from which audio data is sliced. */
	private final WaveDataProvider delegate;

	/** Starting frame index within the delegate provider's data. */
	private final int delegateOffset;

	/** Number of frames to expose from the delegate provider. */
	private final int length;

	/**
	 * Creates a delegate provider that exposes a slice of the given provider's data.
	 *
	 * @param delegate       the source provider to wrap
	 * @param delegateOffset starting frame index within the delegate's data
	 * @param length         number of frames to expose
	 */
	public DelegateWaveDataProvider(WaveDataProvider delegate, int delegateOffset, int length) {
		this.delegate = delegate;
		this.delegateOffset = delegateOffset;
		this.length = length;
	}

	/**
	 * Returns the underlying provider from which audio data is sliced.
	 *
	 * @return the delegate provider
	 */
	public WaveDataProvider getDelegate() {
		return delegate;
	}

	/**
	 * Returns the starting frame index within the delegate provider's data.
	 *
	 * @return frame offset within the delegate
	 */
	public int getDelegateOffset() {
		return delegateOffset;
	}

	@Override
	public long getCountLong() { return length; }

	@Override
	public double getDuration() {
		return length / (double) getSampleRate();
	}

	@Override
	public int getSampleRate() {
		return delegate.getSampleRate();
	}

	@Override
	public int getChannelCount() { return delegate.getChannelCount(); }

	@Override
	public String getKey() {
		return delegate.getKey() + "_" + delegateOffset + ":" + length;
	}

	@Override
	public String getIdentifier() {
		return delegate.getIdentifier() + "_" + delegateOffset + ":" + length;
	}

	@Override
	protected WaveData load() {
		WaveData source = delegate.get();
		return new WaveData(
				new PackedCollection(shape(length), 1,
						source.getData(), delegateOffset),
				source.getSampleRate());
	}
}
