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

import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.Cells;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.arrange.MixdownManager;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.optimize.AudioSceneOptimizer;
import org.almostrealism.audio.pattern.PatternLayerManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.time.TemporalRunner;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.stream.IntStream;

public class AudioSceneOptimizationTest implements CellFeatures {
	public static final boolean enableDelay = true;
	public static final boolean enableFilter = true;

	public static final double delayParam = 0.35;
	public static final double delay = 60 * ((1 / (1 - Math.pow(delayParam, 3))) - 1);
	public static final double speedUpDuration1 = 10.0;
	public static final double speedUpPercentage1 = 0.0;
	public static final double slowDownDuration1 = 10.0;
	public static final double slowDownPercentage1 = 0.0;
	public static final double polySpeedUpDuration1 = 3;
	public static final double polySpeedUpExponent1 = 1.5;
	public static final double speedUpDuration2 = 10.0;
	public static final double speedUpPercentage2 = 0.0;
	public static final double slowDownDuration2 = 10.0;
	public static final double slowDownPercentage2 = 0.0;
	public static final double polySpeedUpDuration2 = 2;
	public static final double polySpeedUpExponent2 = 1.1;

	public static final double feedbackParam = 0.1;

	public static final String sampleFile1 = "Library/Snare Perc DD.wav";
	public static final String sampleFile2 = "Library/GT_HAT_31.wav";

	public AudioScene<?> pattern(int sources, int delayLayers) {
		return pattern(sources, delayLayers, false);
	}

	protected AudioScene<?> pattern(int sources, int delayLayers, boolean sections) {
		try {
			AudioScene<?> scene = new AudioScene<>(120, sources, delayLayers, OutputLine.sampleRate);
			scene.setTotalMeasures(16);
			scene.setTuning(new DefaultKeyboardTuning());

			scene.loadPatterns(SystemUtils.getLocalDestination("pattern-factory.json"));
			scene.setLibraryRoot(new FileWaveDataProviderNode(new File(AudioSceneOptimizer.LIBRARY)));

			PatternLayerManager layer = scene.getPatternManager().addPattern(4, 1.0, true);
			layer.setLayerCount(3);

			if (sections) {
				scene.addSection(0, 16);
			}

			return scene;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void pattern() {
		AudioScene pattern = pattern(2, 2, true);
		pattern.assignGenome(pattern.getGenome().random());

		OperationList setup = new OperationList();
		setup.add(pattern.getTimeManager().setup());

		CellList cells = pattern.getPatternChannel(new ChannelInfo(0, ChannelInfo.Voicing.MAIN, null), pattern.getTotalSamples(), setup);
		cells.addSetup(() -> setup);
		cells.o(i -> new File("results/pattern-test.wav")).sec(20).get().run();
	}

	public Cells randomOrgan(AudioScene<?> scene, MultiChannelAudioOutput output) {
		scene.assignGenome(scene.getGenome().random());
		return scene.getCells(output);
	}

	@Test
	public void withOutput() {
		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;

		WaveOutput output = new WaveOutput(new File("results/genetic-factory-test.wav"));
		Cells organ = randomOrgan(pattern(2, 2), new MultiChannelAudioOutput(output));
		organ.sec(6).get().run();
		output.write().get().run();
	}

	@Test
	public void many() {
		WaveOutput out = new WaveOutput(new File("results/organ-factory-many-test.wav"));
		Cells organ = randomOrgan(pattern(2, 2), new MultiChannelAudioOutput(out));

		Runnable run = new TemporalRunner(organ, 8 * OutputLine.sampleRate).get();

		IntStream.range(0, 10).forEach(i -> {
			run.run();
			out.write().get().run();
			organ.reset();
		});
	}

	@Test
	public void random() {
		WaveOutput out = new WaveOutput(new File("factory-rand-test.wav"));
		Cells organ = randomOrgan(pattern(2, 2), new MultiChannelAudioOutput(out));
		organ.reset();

		Runnable organRun = new TemporalRunner(organ, 8 * OutputLine.sampleRate).get();
		organRun.run();
		out.write().get().run();
	}
}
