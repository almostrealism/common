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
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;

/**
 * Base interface for audio data storage used in temporal audio processing.
 *
 * <p>{@code BaseAudioData} defines the fundamental memory layout and accessors
 * for audio processing state. It provides storage for common audio parameters
 * including wave position, wave length, and amplitude.</p>
 *
 * <p>Memory layout (first 3 slots):</p>
 * <ul>
 *   <li>Slot 0: Wave position - current playback position within the waveform</li>
 *   <li>Slot 1: Wave length - duration or size of the waveform</li>
 *   <li>Slot 2: Amplitude - volume/intensity multiplier</li>
 * </ul>
 *
 * <p>Implementations provide the actual storage via the {@link #get(int)} method,
 * which returns {@link Scalar} instances for each slot.</p>
 *
 * @author Michael Murray
 * @see WaveCellData
 * @see DefaultWaveCellData
 */
public interface BaseAudioData extends CodeFeatures {

	/**
	 * Returns the scalar storage at the specified index.
	 *
	 * @param index the storage slot index
	 * @return the scalar at the specified index
	 */
	Scalar get(int index);

	/**
	 * Returns the scalar storage for the wave position.
	 *
	 * @return the wave position scalar at slot 0
	 */
	default Scalar wavePosition() { return get(0); }

	/**
	 * Returns the scalar storage for the wave length.
	 *
	 * @return the wave length scalar at slot 1
	 */
	default Scalar waveLength() { return get(1); }

	/**
	 * Returns the scalar storage for the amplitude.
	 *
	 * @return the amplitude scalar at slot 2
	 */
	default Scalar amplitude() { return get(2); }

	/**
	 * Returns a producer for the wave position value.
	 *
	 * @return producer providing the current wave position
	 */
	default Producer<PackedCollection<?>> getWavePosition() {
		return p(wavePosition().range(shape(1)));
	}

	/**
	 * Sets the current wave position.
	 *
	 * @param wavePosition the position within the waveform
	 */
	default void setWavePosition(double wavePosition) {
		wavePosition().setValue(wavePosition);
	}

	/**
	 * Returns a producer for the wave length value.
	 *
	 * @return producer providing the wave length
	 */
	default Producer<PackedCollection<?>> getWaveLength() {
		return p(waveLength().range(shape(1)));
	}

	/**
	 * Sets the wave length.
	 *
	 * @param waveLength the duration or size of the waveform
	 */
	default void setWaveLength(double waveLength) {
		waveLength().setValue(waveLength);
	}

	/**
	 * Returns a producer for the amplitude value.
	 *
	 * @return producer providing the amplitude multiplier
	 */
	default Producer<PackedCollection<?>> getAmplitude() {
		return p(amplitude().range(shape(1)));
	}

	/**
	 * Sets the amplitude multiplier.
	 *
	 * @param amplitude the volume/intensity multiplier (1.0 = original level)
	 */
	default void setAmplitude(double amplitude) {
		amplitude().setValue(amplitude);
	}
}
