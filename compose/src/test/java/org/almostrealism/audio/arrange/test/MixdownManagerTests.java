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

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.arrange.AutomationManager;
import org.almostrealism.audio.arrange.GlobalTimeManager;
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.TemporalRunner;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MixdownManagerTests implements CellFeatures {
	private final double duration = 180;
	private final int sampleRate = OutputLine.sampleRate;

	protected void run(String name, GlobalTimeManager time, MixdownManager mixdown, CellList cells) {
		OperationList setup = new OperationList("MixdownManagerTests Setup");
		setup.add(mixdown.getAutomationManager().setup());
		setup.add(mixdown.setup());
		setup.add(time.setup());

		MultiChannelAudioOutput output = new MultiChannelAudioOutput();

		List<WaveOutput> stemsOut = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++)
			stemsOut.add(new WaveOutput(new File("results/" + name + "-stem" + i + ".wav")));

		stemsOut.add(new WaveOutput(new File("results/" + name + "-efx.wav")));

		WaveOutput mixOut = new WaveOutput(new File("results/" + name + "-mix.wav"));

		cells = mixdown.cells(cells, output, ChannelInfo.StereoChannel.LEFT);
		cells.addRequirement(time::tick);

		setup.get().run();
		TemporalRunner runner = new TemporalRunner(cells, (int) (duration * sampleRate));
		runner.get().run();
		stemsOut.forEach(s -> s.write().get().run());
		mixOut.write().get().run();
	}

	@Test
	public void mixdown1() throws IOException {
		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableReverb = true;
		MixdownManager.enableTransmission = true;
		MixdownManager.enableWetInAdjustment = true;
		MixdownManager.enableMasterFilterDown = true;
		MixdownManager.disableClean = false;
		MixdownManager.enableSourcesOnly = false;

		double measureDuration = Frequency.forBPM(120).l(4);

		GlobalTimeManager time = new GlobalTimeManager(
				measure -> (int) (measure * measureDuration * sampleRate));

		int params = 8;
		ProjectedGenome genome = new ProjectedGenome(params);
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(),
				() -> measureDuration, sampleRate);
		MixdownManager mixdown = new MixdownManager(genome.addChromosome(), 2, 3,
										automation, time.getClock(), sampleRate);
		mixdown.setReverbChannels(List.of(0, 1));


		genome.assignTo(new PackedCollection(params).randFill());

		CellList cells = w(0, c(0.0), c(1.0),
				WaveData.load(new File("Library/Snare Gold 1.wav")),
				WaveData.load(new File("Library/SN_Forever_Future.wav")));
		run("mixdown1", time, mixdown, cells);
	}
}
