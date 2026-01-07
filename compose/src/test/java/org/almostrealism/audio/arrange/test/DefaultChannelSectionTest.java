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

package org.almostrealism.audio.arrange.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.arrange.ChannelSection;
import org.almostrealism.audio.arrange.DefaultChannelSectionFactory;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.time.Frequency;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class DefaultChannelSectionTest implements CellFeatures {
	@Test
	public void section() throws IOException {
		int samples = 2 * 8 * OutputLine.sampleRate;

		ProjectedGenome genome = new ProjectedGenome(8);
		DefaultChannelSectionFactory factory = new DefaultChannelSectionFactory(genome.addChromosome(),
											1, c -> true, c -> true,
											() -> Frequency.forBPM(120.0), () -> 2.0,
											8, OutputLine.sampleRate);
		ChannelSection section = factory.createSection(0);

		WaveData data = WaveData.load(new File("Library/Snare Perc DD.wav"));
		PackedCollection input = new PackedCollection(samples);
		input.setMem(data.getChannelData(0).toArray());

		PackedCollection result = new PackedCollection(samples);
		Producer<PackedCollection> destination = p(result);
		Producer<PackedCollection> source = p(input);

		OperationList process = new OperationList();
		process.add(factory.setup());
		process.add(section.process(destination, source));

		process.get().run();
		System.out.println("Processed " + result.getMemLength() + " samples");
	}
}
