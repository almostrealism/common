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
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * An audio cell that dynamically selects between multiple processing choices in sequential mode.
 *
 * <p>PolymorphicAudioCell uses a Switch computation to execute only the selected choice,
 * with all choices sharing the same {@link PolymorphicAudioData} storage. This is more
 * efficient when only one processor needs to run at a time and state can be shared,
 * such as a single instrument that can produce different timbres.</p>
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>All choice cells share the same PolymorphicAudioData instance</li>
 *   <li>Only the selected cell executes based on the decision value</li>
 *   <li>Switch computation routes to the appropriate setup/push/tick operation</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create shared data
 * PolymorphicAudioData data = new PolymorphicAudioData();
 *
 * // Create decision producer
 * CollectionProducer decision = c(1);  // Select second choice
 *
 * // Create choices that share data
 * List<Function<PolymorphicAudioData, CollectionTemporalCellAdapter>> choices = List.of(
 *     d -> createAttackSound(d),
 *     d -> createSustainSound(d),
 *     d -> createReleaseSound(d)
 * );
 *
 * // Create polymorphic cell
 * PolymorphicAudioCell cell = new PolymorphicAudioCell(data, decision, choices);
 * }</pre>
 *
 * @see AudioCellChoiceAdapter
 * @see DynamicAudioCell
 * @see PolymorphicAudioData
 */
public class PolymorphicAudioCell extends AudioCellChoiceAdapter {

	public PolymorphicAudioCell(PolymorphicAudioData data, CollectionProducer decision,
								Function<PolymorphicAudioData, ? extends CollectionTemporalCellAdapter>... choices) {
		this(data, decision, Arrays.asList(choices));
	}

	public PolymorphicAudioCell(PolymorphicAudioData data, CollectionProducer decision,
								List<Function<PolymorphicAudioData, ? extends CollectionTemporalCellAdapter>> choices) {
		super(decision, i -> data, choices, false);
	}

	public PolymorphicAudioCell(CollectionProducer decision,
								List<CollectionTemporalCellAdapter> choices) {
		super(decision, choices, false);
	}
}
