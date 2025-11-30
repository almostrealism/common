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

public class DelegateWaveDataProvider extends WaveDataProviderAdapter {
	private final WaveDataProvider delegate;
	private final int delegateOffset;
	private final int length;

	public DelegateWaveDataProvider(WaveDataProvider delegate, int delegateOffset, int length) {
		this.delegate = delegate;
		this.delegateOffset = delegateOffset;
		this.length = length;
	}

	public WaveDataProvider getDelegate() {
		return delegate;
	}

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
