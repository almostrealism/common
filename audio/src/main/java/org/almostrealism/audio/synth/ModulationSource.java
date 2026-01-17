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

package org.almostrealism.audio.synth;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.Temporal;

/**
 * Interface for modulation sources that produce time-varying control signals.
 * <p>
 * Modulation sources output values typically in the range [-1, 1] or [0, 1]
 * that can be scaled and applied to synthesis parameters.
 *
 * @see LFO
 * @see org.almostrealism.audio.filter.ADSREnvelope
 */
public interface ModulationSource extends Temporal {

	/**
	 * Returns the current modulation output value.
	 * <p>
	 * For bipolar sources (like LFO sine), the range is [-1, 1].
	 * For unipolar sources (like envelopes), the range is [0, 1].
	 *
	 * @return the current modulation value
	 */
	double getValue();

	/**
	 * Returns a producer for the modulation output.
	 */
	Producer<PackedCollection> getOutput();

	/**
	 * Returns true if this is a bipolar source (output range [-1, 1]).
	 */
	default boolean isBipolar() {
		return false;
	}
}
