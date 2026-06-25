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

package org.almostrealism.audio.sequence;

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.HybridScope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.BaseAudioData;

/**
 * Advances the wave position in a value sequence by the wave length, with optional
 * modulo wrapping for repeating sequences. This computation updates the temporal
 * state without producing output values.
 *
 * @see ValueSequenceCell
 * @see ValueSequenceComputation
 */
public class ValueSequenceTick extends ValueSequenceComputation {
	/**
	 * Creates a repeating ValueSequenceTick that advances through the given steps.
	 *
	 * @param data           audio state data for wave position tracking
	 * @param durationFrames duration of each step in frames
	 * @param choices        value producers (used only for determining step count)
	 */
	public ValueSequenceTick(BaseAudioData data,
							 Producer<PackedCollection> durationFrames,
							 Producer<PackedCollection>... choices) {
		this(data, durationFrames, true, choices);
	}

	/**
	 * Creates a ValueSequenceTick with configurable repeat behavior.
	 *
	 * @param data           audio state data for wave position tracking
	 * @param durationFrames duration of each step in frames
	 * @param repeat         whether the sequence repeats after the last step
	 * @param choices        value producers (used only for determining step count)
	 */
	public ValueSequenceTick(BaseAudioData data,
							 Producer<PackedCollection> durationFrames,
							 boolean repeat, Producer<PackedCollection>... choices) {
		super(data, durationFrames, new PackedCollection(1), repeat, choices);
	}

	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		scope = new HybridScope(this);

		scope.assign(getWavePosition().reference(e(0)),
				getWavePosition().valueAt(0).add(getWaveLength().valueAt(0)));

		if (repeat) {
			scope.assign(getWavePosition().reference(e(0)),
					getWavePosition().valueAt(0).mod(getDurationFrames().valueAt(0)));
		}
	}
}
