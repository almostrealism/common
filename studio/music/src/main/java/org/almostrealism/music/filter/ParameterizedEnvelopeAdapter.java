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

package org.almostrealism.music.filter;

import org.almostrealism.music.data.ParameterFunction;

/**
 * Abstract base class for parameterized ADSR envelope implementations.
 *
 * <p>Stores four {@link ParameterFunction}s that select the attack, decay, sustain,
 * and release envelope parameters from a {@link ParameterSet}. Concrete subclasses
 * implement {@link #createFilter} to produce the actual audio filter.</p>
 *
 * @see ParameterizedVolumeEnvelope
 * @see ParameterizedFilterEnvelope
 */
public abstract class ParameterizedEnvelopeAdapter implements ParameterizedEnvelope {
	/** Selects the attack duration from a parameter set. */
	private ParameterFunction attackSelection;

	/** Selects the decay duration from a parameter set. */
	private ParameterFunction decaySelection;

	/** Selects the sustain level from a parameter set. */
	private ParameterFunction sustainSelection;

	/** Selects the release duration from a parameter set. */
	private ParameterFunction releaseSelection;

	/** Creates a {@code ParameterizedEnvelopeAdapter} with null selection functions. */
	public ParameterizedEnvelopeAdapter() {
	}

	/**
	 * Creates a {@code ParameterizedEnvelopeAdapter} with the given selection functions.
	 *
	 * @param attackSelection  function selecting the attack duration
	 * @param decaySelection   function selecting the decay duration
	 * @param sustainSelection function selecting the sustain level
	 * @param releaseSelection function selecting the release duration
	 */
	public ParameterizedEnvelopeAdapter(ParameterFunction attackSelection,
										ParameterFunction decaySelection,
										ParameterFunction sustainSelection,
										ParameterFunction releaseSelection) {
		this.attackSelection = attackSelection;
		this.decaySelection = decaySelection;
		this.sustainSelection = sustainSelection;
		this.releaseSelection = releaseSelection;
	}


	/** Returns the function that selects the attack duration. */
	public ParameterFunction getAttackSelection() { return attackSelection; }

	/** Sets the function that selects the attack duration. */
	public void setAttackSelection(ParameterFunction attackSelection) { this.attackSelection = attackSelection; }

	/** Returns the function that selects the decay duration. */
	public ParameterFunction getDecaySelection() { return decaySelection; }

	/** Sets the function that selects the decay duration. */
	public void setDecaySelection(ParameterFunction decaySelection) { this.decaySelection = decaySelection; }

	/** Returns the function that selects the sustain level. */
	public ParameterFunction getSustainSelection() { return sustainSelection; }

	/** Sets the function that selects the sustain level. */
	public void setSustainSelection(ParameterFunction sustainSelection) { this.sustainSelection = sustainSelection; }

	/** Returns the function that selects the release duration. */
	public ParameterFunction getReleaseSelection() { return releaseSelection; }

	/** Sets the function that selects the release duration. */
	public void setReleaseSelection(ParameterFunction releaseSelection) { this.releaseSelection = releaseSelection; }
}
