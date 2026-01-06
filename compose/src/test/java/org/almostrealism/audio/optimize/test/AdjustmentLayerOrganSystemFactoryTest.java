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

package org.almostrealism.audio.optimize.test;

import org.almostrealism.audio.Cells;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.time.TemporalRunner;
import org.junit.Test;

import java.io.File;

public class AdjustmentLayerOrganSystemFactoryTest extends AudioSceneOptimizationTest {

	@Test
	public void compare() {
		dc(() -> {
			WaveOutput outa = new WaveOutput(new File("results/layered-organ-factory-comp-a.wav"));
			Cells organa = randomOrgan(pattern(2, 2), new MultiChannelAudioOutput(outa));
			organa.reset();

			WaveOutput outb = new WaveOutput(new File("results/layered-organ-factory-comp-b.wav"));
			Cells organb = randomOrgan(pattern(2, 2),  new MultiChannelAudioOutput(outb));
			organb.reset();

			Runnable organRunA = new TemporalRunner(organa, 8 * OutputLine.sampleRate).get();
			Runnable organRunB = new TemporalRunner(organb, 8 * OutputLine.sampleRate).get();

			organRunA.run();
			outa.write().get().run();

			organRunB.run();
			outb.write().get().run();
		});
	}

	@Test
	public void layered() {
		WaveOutput out = new WaveOutput(new File("results/layered-organ-factory-test.wav"));
		Cells organ = randomOrgan(pattern(2, 2), new MultiChannelAudioOutput(out));
		organ.reset();

		Runnable organRun = new TemporalRunner(organ, 8 * OutputLine.sampleRate).get();
		organRun.run();
		out.write().get().run();

		organRun.run();
		out.write().get().run();
	}

	@Test
	public void layeredRandom() {
		WaveOutput out = new WaveOutput(new File("results/layered-organ-factory-rand-test.wav"));
		Cells organ = randomOrgan(pattern(2, 2), new MultiChannelAudioOutput(out));
		organ.reset();

		Runnable organRun = new TemporalRunner(organ, 8 * OutputLine.sampleRate).get();
		organRun.run();
		out.write().get().run();
	}
}
