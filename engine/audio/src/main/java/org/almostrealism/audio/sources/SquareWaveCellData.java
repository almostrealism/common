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

package org.almostrealism.audio.sources;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Data interface for square wave generation state.
 * Extends {@link SineWaveCellData} to add duty cycle parameter for
 * pulse-width modulation (PWM) support.
 *
 * @see SineWaveCellData
 * @see SquareWaveCell
 */
public interface SquareWaveCellData extends SineWaveCellData {

	/**
	 * Returns the storage for duty cycle (slot 7).
	 * Duty cycle controls the ratio of high to low in the square wave.
	 * 0.5 = standard 50% duty cycle
	 * 0.1 = narrow pulse
	 * 0.9 = wide pulse
	 *
	 * @return the duty cycle storage
	 */
	default PackedCollection dutyCycle() { return get(7); }

	/**
	 * Returns a producer for the duty cycle value.
	 *
	 * @return producer providing the duty cycle
	 */
	default Producer<PackedCollection> getDutyCycle() {
		return cp(dutyCycle().range(shape(1)));
	}

	/**
	 * Sets the duty cycle value.
	 *
	 * @param dutyCycle the duty cycle (0.0 to 1.0, default 0.5)
	 */
	default void setDutyCycle(double dutyCycle) {
		dutyCycle().setMem(0, dutyCycle);
	}
}
