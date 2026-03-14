/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.synth;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * An {@link AudioSynthesisModel} directs the synthesis of audio by
 * providing a time series of levels for a provided frequency ratio,
 * relative to the fundamental (or "root") frequency.
 */
public interface AudioSynthesisModel {
	/**
	 * Provides a time series of levels for the specified frequency ratio.
	 *
	 * @param frequencyRatio the frequency ratio to produce levels for
	 * @param time  the time values the levels are to be produced for
	 * @return a {@link Producer} that produces a time series of levels
	 */
	Producer<PackedCollection> getLevels(double frequencyRatio, Producer<PackedCollection> time);
}
