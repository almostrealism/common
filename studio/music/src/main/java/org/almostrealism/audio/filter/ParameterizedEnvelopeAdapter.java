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

import org.almostrealism.audio.data.ParameterFunction;

public abstract class ParameterizedEnvelopeAdapter implements ParameterizedEnvelope {
	private ParameterFunction attackSelection;
	private ParameterFunction decaySelection;
	private ParameterFunction sustainSelection;
	private ParameterFunction releaseSelection;

	public ParameterizedEnvelopeAdapter() {
	}

	public ParameterizedEnvelopeAdapter(ParameterFunction attackSelection,
										ParameterFunction decaySelection,
										ParameterFunction sustainSelection,
										ParameterFunction releaseSelection) {
		this.attackSelection = attackSelection;
		this.decaySelection = decaySelection;
		this.sustainSelection = sustainSelection;
		this.releaseSelection = releaseSelection;
	}


	public ParameterFunction getAttackSelection() { return attackSelection; }
	public void setAttackSelection(ParameterFunction attackSelection) { this.attackSelection = attackSelection; }

	public ParameterFunction getDecaySelection() { return decaySelection; }
	public void setDecaySelection(ParameterFunction decaySelection) { this.decaySelection = decaySelection; }

	public ParameterFunction getSustainSelection() { return sustainSelection; }
	public void setSustainSelection(ParameterFunction sustainSelection) { this.sustainSelection = sustainSelection; }

	public ParameterFunction getReleaseSelection() { return releaseSelection; }
	public void setReleaseSelection(ParameterFunction releaseSelection) { this.releaseSelection = releaseSelection; }
}
