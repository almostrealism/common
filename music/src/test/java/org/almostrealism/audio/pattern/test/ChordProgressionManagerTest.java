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

package org.almostrealism.audio.pattern.test;

import org.almostrealism.audio.pattern.ChordProgressionManager;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.junit.Test;

import java.util.stream.IntStream;

public class ChordProgressionManagerTest {
	@Test
	public void progression() {
		int params = 8;
		ProjectedGenome genome = new ProjectedGenome(params);

		ChordProgressionManager progression = new ChordProgressionManager(genome.addChromosome(),
											WesternScales.minor(WesternChromatic.G1, 1));
		progression.setSize(8);
		progression.setDuration(16);

		IntStream.range(0, 10).forEach(i -> {
			genome.assignTo(new PackedCollection(params).randFill());
			progression.refreshParameters();
			System.out.println(progression.getRegionString());
		});
	}
}
