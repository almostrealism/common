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

package org.almostrealism.audio.line;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * An audio input line for reading audio data from a source (e.g., microphone, line-in).
 * Extends {@link BufferedAudio} for buffering configuration and {@link Destroyable}
 * for lifecycle management.
 */
public interface InputLine extends BufferedAudio, Destroyable {
	/**
	 * Returns the position in the buffer where the last frame was written by the device.
	 * This is used to track how much data has been captured and is available for reading.
	 *
	 * @return The write position in frames, or 0 if not tracking
	 */
	default int getWritePosition() {
		return 0;
	}

	/**
	 * Read all the samples that can fit in the provided {@link PackedCollection},
	 * which cannot be larger than the buffer size.
	 *
	 * @see  BufferedAudio#getBufferSize()
	 */
	void read(PackedCollection sample);

	default Supplier<Runnable> read(Producer<PackedCollection> destination) {
		return () -> {
			Evaluable<PackedCollection> sample = destination.get();
			return () -> read(sample.evaluate());
		};
	}
}
