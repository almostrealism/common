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

package org.almostrealism.studio.optimize.test;

import org.almostrealism.studio.AudioScene;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertTrue;

public class AudioSceneOptimizationTest extends TestSuiteBase implements CellFeatures {
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

	@Before
	public void checkResources() {
		Assume.assumeTrue("Library directory required",
				new File(AudioSceneOptimizer.LIBRARY).exists());
		Assume.assumeTrue("pattern-factory.json required",
				new File(SystemUtils.getLocalDestination("pattern-factory.json")).exists());
	}

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

	@Test(timeout = 300_000)
	public void pattern() {
		AudioScene<?> pattern = pattern(2, 2, true);
		pattern.assignGenome(pattern.getGenome().random());

		OperationList setup = new OperationList();
		setup.add(pattern.getTimeManager().setup());

		CellList cells = pattern.getPatternChannel(new ChannelInfo(0, ChannelInfo.Voicing.MAIN, null), pattern.getTotalSamples(), () -> 0, setup);
		cells.addSetup(() -> setup);
		File outFile = new File("results/pattern-test.wav");
		outFile.getParentFile().mkdirs();
		cells.o(i -> outFile).sec(20).get().run();

		assertTrue("Pattern channel WAV should exist", outFile.exists());
		assertTrue("Pattern channel WAV should not be empty", outFile.length() > 1024);
	}

	public TemporalCellular randomOrgan(AudioScene<?> scene, MultiChannelAudioOutput output, int bufferSize) {
		scene.assignGenome(scene.getGenome().random());
		return scene.runnerRealTime(output, bufferSize);
	}

	private static void render(TemporalCellular runner, int bufferSize, double seconds) {
		runner.setup().get().run();
		Runnable tick = runner.tick().get();
		int totalFrames = (int) (seconds * OutputLine.sampleRate);
		int bufferCount = (totalFrames + bufferSize - 1) / bufferSize;
		for (int i = 0; i < bufferCount; i++) {
			tick.run();
		}
	}

	@Test(timeout = 180_000)
	@TestDepth(1)
	public void withOutput() {
		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableEfx = false;

		File outFile = new File("results/genetic-factory-test.wav");
		outFile.getParentFile().mkdirs();
		WaveOutput output = new WaveOutput(outFile);
		int bufferSize = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;
		TemporalCellular runner = randomOrgan(pattern(2, 2),
				new MultiChannelAudioOutput(output), bufferSize);
		render(runner, bufferSize, 6.0);
		output.write().get().run();

		assertTrue("Output WAV should exist", outFile.exists());
		assertTrue("Output WAV should not be empty", outFile.length() > 1024);
	}

	@Test(timeout = 600_000)
	@TestDepth(2)
	public void many() {
		File outFile = new File("results/organ-factory-many-test.wav");
		outFile.getParentFile().mkdirs();
		WaveOutput out = new WaveOutput(outFile);
		int bufferSize = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;
		TemporalCellular runner = randomOrgan(pattern(2, 2),
				new MultiChannelAudioOutput(out), bufferSize);

		for (int i = 0; i < 10; i++) {
			render(runner, bufferSize, 8.0);
			out.write().get().run();
			runner.reset();
		}

		assertTrue("Output WAV should exist after multiple renders", outFile.exists());
		assertTrue("Output WAV should not be empty", outFile.length() > 1024);
	}

	@Test(timeout = 180_000)
	@TestDepth(1)
	public void random() {
		File outFile = new File("results/factory-rand-test.wav");
		outFile.getParentFile().mkdirs();
		WaveOutput out = new WaveOutput(outFile);
		int bufferSize = AudioScene.DEFAULT_REALTIME_BUFFER_SIZE;
		TemporalCellular runner = randomOrgan(pattern(2, 2),
				new MultiChannelAudioOutput(out), bufferSize);
		runner.reset();

		render(runner, bufferSize, 8.0);
		out.write().get().run();

		assertTrue("Output WAV should exist", outFile.exists());
		assertTrue("Output WAV should not be empty", outFile.length() > 1024);
	}
}
