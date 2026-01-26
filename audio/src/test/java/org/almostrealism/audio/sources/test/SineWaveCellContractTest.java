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

package org.almostrealism.audio.sources.test;

import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.audio.test.CellContractTest;

/**
 * Contract test for {@link SineWaveCell}.
 * <p>
 * This verifies that SineWaveCell adheres to the Cell contract.
 * SineWaveCell is a reference implementation of a well-behaved Cell.
 *
 * @see CellContractTest
 */
public class SineWaveCellContractTest extends CellContractTest<SineWaveCell> {

	@Override
	protected SineWaveCell createCell() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);  // A4
		cell.setNoteLength(1000);  // 1 second note
		return cell;
	}

	@Override
	protected void configureForAudioGeneration(SineWaveCell cell) {
		// Set non-zero amplitude so we get output
		cell.setAmplitude(0.5);
	}
}
