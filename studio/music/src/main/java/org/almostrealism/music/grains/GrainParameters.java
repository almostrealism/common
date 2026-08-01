/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.music.grains;

import org.almostrealism.music.data.ParameterFunction;

/**
 * Holds the {@link ParameterFunction} instances that control amplitude, phase, and wavelength
 * for a single grain during granular synthesis rendering.
 *
 * <p>Each parameter function maps a {@link org.almostrealism.music.data.ParameterSet} to a
 * scalar value used by the grain engine when generating audio output.</p>
 *
 * @see GrainSet
 * @see GrainProcessor
 */
public class GrainParameters {
	/** Function that determines the grain amplitude (volume scaling). */
	private ParameterFunction amp;

	/** Function that determines the grain phase offset. */
	private ParameterFunction phase;

	/** Function that determines the grain wavelength (playback period). */
	private ParameterFunction wavelength;

	/** Creates an uninitialized {@code GrainParameters} instance. */
	public GrainParameters() { }

	/**
	 * Creates a {@code GrainParameters} with the given parameter functions.
	 *
	 * @param amp        function controlling grain amplitude
	 * @param phase      function controlling grain phase offset
	 * @param wavelength function controlling grain wavelength
	 */
	public GrainParameters(ParameterFunction amp, ParameterFunction phase, ParameterFunction wavelength) {
		this.amp = amp;
		this.phase = phase;
		this.wavelength = wavelength;
	}

	/** Returns the amplitude parameter function. */
	public ParameterFunction getAmp() {
		return amp;
	}

	/** Sets the amplitude parameter function. */
	public void setAmp(ParameterFunction amp) {
		this.amp = amp;
	}

	/** Returns the phase parameter function. */
	public ParameterFunction getPhase() {
		return phase;
	}

	/** Sets the phase parameter function. */
	public void setPhase(ParameterFunction phase) {
		this.phase = phase;
	}

	/** Returns the wavelength parameter function. */
	public ParameterFunction getWavelength() {
		return wavelength;
	}

	/** Sets the wavelength parameter function. */
	public void setWavelength(ParameterFunction wavelength) {
		this.wavelength = wavelength;
	}

	/**
	 * Creates a randomly initialized {@code GrainParameters} instance.
	 *
	 * @return a new instance with random amplitude, phase, and wavelength functions
	 */
	public static GrainParameters random() {
		return new GrainParameters(ParameterFunction.random(), ParameterFunction.random(), ParameterFunction.random());
	}
}
