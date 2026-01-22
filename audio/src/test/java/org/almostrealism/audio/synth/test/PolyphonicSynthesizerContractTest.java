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

package org.almostrealism.audio.synth.test;

import org.almostrealism.audio.synth.PolyphonicSynthesizer;
import org.almostrealism.audio.test.CellContractTest;

/**
 * Contract test for {@link PolyphonicSynthesizer}.
 * <p>
 * This test ensures PolyphonicSynthesizer adheres to the Cell contract:
 * <ul>
 *   <li>push() generates audio and forwards to receptor</li>
 *   <li>tick() only advances state, does NOT generate audio</li>
 *   <li>Behavior is identical regardless of context</li>
 * </ul>
 *
 * @see CellContractTest
 */
public class PolyphonicSynthesizerContractTest extends CellContractTest<PolyphonicSynthesizer> {

	@Override
	protected PolyphonicSynthesizer createCell() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);
		// Use default settings - the contract test doesn't care about sound quality
		return synth;
	}

	@Override
	protected void configureForAudioGeneration(PolyphonicSynthesizer cell) {
		// Trigger a note so the synthesizer produces output
		cell.noteOn(60, 0.8);  // Middle C at 80% velocity
	}

	@Override
	protected int getSampleCount() {
		// Synthesizers may need more samples to warm up envelopes
		return 1000;
	}
}
