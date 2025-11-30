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

package org.almostrealism.audio.sources;

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;

public class BufferDetails {
	private final int sampleRate;
	private final int frames;

	public BufferDetails(int sampleRate, int frames) {
		this.sampleRate = sampleRate;
		this.frames = frames;
	}

	public BufferDetails(int sampleRate, double duration) {
		this.sampleRate = sampleRate;
		this.frames = (int) (duration * sampleRate);
	}

	public int getSampleRate() {
		return sampleRate;
	}

	public int getFrames() {
		return frames;
	}

	public double getDuration() {
		return (double) frames / sampleRate;
	}

	public WaveData createWaveData() {
		return new WaveData(new PackedCollection(getFrames()).traverseEach(), getSampleRate());
	}
}
