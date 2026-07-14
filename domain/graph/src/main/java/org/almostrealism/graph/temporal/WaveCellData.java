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

package org.almostrealism.graph.temporal;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Data interface for wave cell audio processing state.
 *
 * <p>{@code WaveCellData} extends {@link BaseAudioData} to provide additional
 * state storage specific to waveform playback, including the current wave index
 * position and the total wave sample count. This interface defines the memory
 * layout for wave cell operations.</p>
 *
 * <p>Memory layout (inherits slots 0-2 from BaseAudioData):</p>
 * <ul>
 *   <li>Slot 0-2: BaseAudioData fields (wavePosition, waveLength, amplitude)</li>
 *   <li>Slot 3: Wave index - starting position in the wave data</li>
 *   <li>Slot 4: Wave count - total number of samples to process</li>
 *   <li>Slot 9: Value - the current output sample value</li>
 * </ul>
 *
 * @author Michael Murray
 * @see BaseAudioData
 * @see WaveCell
 * @see DefaultWaveCellData
 */
public interface WaveCellData extends BaseAudioData {

	/**
	 * Returns the storage for the wave index position.
	 *
	 * @return the wave index storage at slot 3
	 */
	default PackedCollection waveIndex() { return get(3); }

	/**
	 * Returns the storage for the wave sample count.
	 *
	 * @return the wave count storage at slot 4
	 */
	default PackedCollection waveCount() { return get(4); }

	/**
	 * Returns the current output value as a single-element collection.
	 *
	 * @return the current sample value at slot 9
	 */
	default PackedCollection value() { return get(9).range(shape(1)); }

	/**
	 * Returns a producer for the wave index position.
	 *
	 * @return producer providing the wave index value
	 */
	default Producer<PackedCollection> getWaveIndex() { return p(waveIndex().range(shape(1))); }

	/**
	 * Sets the wave index position.
	 *
	 * @param count the starting index within the wave data
	 */
	default void setWaveIndex(int count) { waveIndex().setMem(0, count); }

	/**
	 * Returns a producer for the wave sample count.
	 *
	 * @return producer providing the wave count value
	 */
	default Producer<PackedCollection> getWaveCount() { return p(waveCount().range(shape(1))); }

	/**
	 * Sets the total number of samples to process.
	 *
	 * @param count the number of samples in the wave data
	 */
	default void setWaveCount(int count) { waveCount().setMem(0, count); }
}

