/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.filter;

import org.almostrealism.collect.PackedCollection;

/**
 * Interface for ADSR envelope-controlled audio processing.
 *
 * @deprecated Use {@link AudioProcessor} with envelope integration instead.
 */
// TODO  Should use AudioProcessor instead
@Deprecated
public interface EnvelopeProcessor {
	/**
	 * Sets the total duration of the envelope in seconds.
	 *
	 * @param duration total envelope duration in seconds
	 */
	void setDuration(double duration);

	/**
	 * Sets the attack time in seconds.
	 *
	 * @param attack attack phase duration in seconds
	 */
	void setAttack(double attack);

	/**
	 * Sets the decay time in seconds.
	 *
	 * @param decay decay phase duration in seconds
	 */
	void setDecay(double decay);

	/**
	 * Sets the sustain level as a fraction of the peak amplitude.
	 *
	 * @param sustain sustain level (0.0–1.0)
	 */
	void setSustain(double sustain);

	/**
	 * Sets the release time in seconds.
	 *
	 * @param release release phase duration in seconds
	 */
	void setRelease(double release);

	/**
	 * Applies the envelope to the input audio and writes the result to the output buffer.
	 *
	 * @param input  input audio data
	 * @param output output buffer for processed audio
	 */
	void process(PackedCollection input, PackedCollection output);
}
