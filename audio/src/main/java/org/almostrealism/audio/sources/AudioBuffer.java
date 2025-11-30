/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.collect.PackedCollection;

public class AudioBuffer {
	private final BufferDetails details;
	private final PackedCollection input;
	private final PackedCollection output;

	public AudioBuffer(BufferDetails details,
					   PackedCollection input,
					   PackedCollection output) {
		this.details = details;
		this.input = input;
		this.output = output;

		if (details.getFrames() != output.getMemLength() ||
				details.getFrames() != input.getMemLength()) {
			throw new IllegalArgumentException();
		}
	}

	public BufferDetails getDetails() { return details; }
	public PackedCollection getInputBuffer() { return input; }
	public PackedCollection getOutputBuffer() { return output; }

	public static AudioBuffer create(int sampleRate, int frames) {
		return new AudioBuffer(
				new BufferDetails(sampleRate, frames),
				new PackedCollection(frames),
				new PackedCollection(frames));
	}
}
