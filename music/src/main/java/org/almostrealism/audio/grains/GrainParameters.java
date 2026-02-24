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

package org.almostrealism.audio.grains;

import org.almostrealism.audio.data.ParameterFunction;

public class GrainParameters {
	private ParameterFunction amp;
	private ParameterFunction phase;
	private ParameterFunction wavelength;

	public GrainParameters() { }

	public GrainParameters(ParameterFunction amp, ParameterFunction phase, ParameterFunction wavelength) {
		this.amp = amp;
		this.phase = phase;
		this.wavelength = wavelength;
	}

	public ParameterFunction getAmp() {
		return amp;
	}

	public void setAmp(ParameterFunction amp) {
		this.amp = amp;
	}

	public ParameterFunction getPhase() {
		return phase;
	}

	public void setPhase(ParameterFunction phase) {
		this.phase = phase;
	}

	public ParameterFunction getWavelength() {
		return wavelength;
	}

	public void setWavelength(ParameterFunction wavelength) {
		this.wavelength = wavelength;
	}

	public static GrainParameters random() {
		return new GrainParameters(ParameterFunction.random(), ParameterFunction.random(), ParameterFunction.random());
	}
}
