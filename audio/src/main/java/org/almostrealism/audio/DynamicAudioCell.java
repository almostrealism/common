/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio;

import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;

import java.util.List;
import java.util.function.Function;

/**
 * An audio cell that dynamically selects between multiple processing choices in parallel mode.
 *
 * <p>DynamicAudioCell executes all choice cells simultaneously, each with its own independent
 * {@link PolymorphicAudioData} storage, and selects the output based on a decision value.
 * This is ideal for scenarios where all processors need to maintain their own state
 * independently, such as multiple synthesizer voices or parallel effect chains.</p>
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>Each choice cell receives its own PolymorphicAudioData bank</li>
 *   <li>All cells execute their setup/push/tick operations in parallel</li>
 *   <li>The decision producer determines which output to route to the receptor</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create decision producer (e.g., based on MIDI note or parameter)
 * CollectionProducer decision = c(0);  // Select first choice
 *
 * // Create choices
 * List<Function<PolymorphicAudioData, CollectionTemporalCellAdapter>> choices = List.of(
 *     data -> createSineWave(data),
 *     data -> createSquareWave(data),
 *     data -> createSawtoothWave(data)
 * );
 *
 * // Create dynamic cell
 * DynamicAudioCell cell = new DynamicAudioCell(decision, choices);
 * }</pre>
 *
 * @see AudioCellChoiceAdapter
 * @see PolymorphicAudioCell
 * @see PolymorphicAudioData
 */
public class DynamicAudioCell extends AudioCellChoiceAdapter {
	public DynamicAudioCell(CollectionProducer decision,
							List<Function<PolymorphicAudioData, ? extends CollectionTemporalCellAdapter>> choices) {
		this(PolymorphicAudioData.bank(choices.size()), decision, choices);
	}

	public DynamicAudioCell(PackedCollection data, CollectionProducer decision,
							List<Function<PolymorphicAudioData, ? extends CollectionTemporalCellAdapter>> choices) {
		super(decision, i -> new PolymorphicAudioData(data, i * PolymorphicAudioData.SIZE * 2), choices, true);
	}
}

