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

package org.almostrealism.studio.arrange.test;

import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.arrange.AutomationManager;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for MixdownManager audio mixdown functionality.
 */
public class MixdownManagerTests extends TestSuiteBase implements CellFeatures, AudioTestFeatures {
	/** Duration of the mixdown test in seconds. */
	private final double duration = 180;

	/** Sample rate for audio processing. */
	private final int sampleRate = OutputLine.sampleRate;

	/**
	 * Captures the current value of every {@link MixdownManager} static flag mutated
	 * by the tests below, returning a {@link Runnable} that restores them. Centralizing
	 * this here keeps the tests order-independent even as new flags are set for a
	 * particular scenario, since restoring is not tied to the specific flags a test
	 * happens to change.
	 */
	private Runnable captureFlags() {
		boolean enableMixdown = MixdownManager.enableMixdown;
		boolean enableSourcesOnly = MixdownManager.enableSourcesOnly;
		boolean disableClean = MixdownManager.disableClean;
		boolean enableMainFilterUp = MixdownManager.enableMainFilterUp;
		boolean enableAutomationManager = MixdownManager.enableAutomationManager;
		boolean enableEfxFilters = MixdownManager.enableEfxFilters;
		boolean enableEfx = MixdownManager.enableEfx;
		boolean enableReverb = MixdownManager.enableReverb;
		boolean enableTransmission = MixdownManager.enableTransmission;
		boolean enableWetInAdjustment = MixdownManager.enableWetInAdjustment;
		boolean enableMasterFilterDown = MixdownManager.enableMasterFilterDown;
		boolean enableRiser = MixdownManager.enableRiser;
		boolean enableWetSources = MixdownManager.enableWetSources;

		return () -> {
			MixdownManager.enableMixdown = enableMixdown;
			MixdownManager.enableSourcesOnly = enableSourcesOnly;
			MixdownManager.disableClean = disableClean;
			MixdownManager.enableMainFilterUp = enableMainFilterUp;
			MixdownManager.enableAutomationManager = enableAutomationManager;
			MixdownManager.enableEfxFilters = enableEfxFilters;
			MixdownManager.enableEfx = enableEfx;
			MixdownManager.enableReverb = enableReverb;
			MixdownManager.enableTransmission = enableTransmission;
			MixdownManager.enableWetInAdjustment = enableWetInAdjustment;
			MixdownManager.enableMasterFilterDown = enableMasterFilterDown;
			MixdownManager.enableRiser = enableRiser;
			MixdownManager.enableWetSources = enableWetSources;
		};
	}

	/**
	 * Runs the mixdown process with the given parameters.
	 */
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

	/**
	 * Test that MixdownManager produces correct mixdown output with effects.
	 */
	@Test(timeout = 600_000)
	@TestProperties(knownIssue = true)
	@TestDepth(1)
	public void mixdown1() throws IOException {
		Runnable restoreFlags = captureFlags();
		try {
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

			// Use synthetic test audio files instead of Library/ samples
			File testAudio1 = getTestWavFile(440.0, 2.0);
			File testAudio2 = getTestWavFile(880.0, 2.0);

			CellList cells = w(0, c(0.0), c(1.0),
					WaveData.load(testAudio1),
					WaveData.load(testAudio2));
			run("mixdown1", time, mixdown, cells);
		} finally {
			restoreFlags.run();
		}
	}

	/**
	 * Builds a {@link MixdownManager} wired the same way as {@link #mixdown1()},
	 * for use by the mono-output regression tests below.
	 */
	private MixdownManager mono(GlobalTimeManager time, ProjectedGenome genome, int params) {
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(), () -> 1.0, sampleRate);
		MixdownManager mixdown = new MixdownManager(genome.addChromosome(), 2, 3,
										automation, time.getClock(), sampleRate);
		mixdown.setReverbChannels(List.of(0, 1));
		genome.assignTo(new PackedCollection(params).randFill());
		return mixdown;
	}

	/**
	 * A mono {@link MultiChannelAudioOutput} exposes no master receptor for the
	 * RIGHT stereo channel. Building the EFX graph for that channel must not throw,
	 * even though {@link MixdownManager#createEfx} feeds {@code output.getMaster}
	 * into every one of its Receptor delivery sites.
	 */
	@Test(timeout = 60000)
	@TestDepth(1)
	public void efxRoutingWithMonoOutput() throws IOException {
		Runnable restoreFlags = captureFlags();
		try {
			MixdownManager.enableMainFilterUp = true;
			MixdownManager.enableEfxFilters = true;
			MixdownManager.enableEfx = true;
			MixdownManager.enableReverb = true;
			// Transmission delay lines allocate large native buffers sized from
			// per-gene delay durations; disabled here since this test targets
			// Receptor null-filtering, not the delay network.
			MixdownManager.enableTransmission = false;
			MixdownManager.enableWetInAdjustment = true;
			MixdownManager.enableMasterFilterDown = true;
			MixdownManager.disableClean = false;
			MixdownManager.enableSourcesOnly = false;

			GlobalTimeManager time = new GlobalTimeManager(measure -> 0);
			int params = 8;
			ProjectedGenome genome = new ProjectedGenome(params);
			MixdownManager mixdown = mono(time, genome, params);

			CellList cells = w(0, c(0.0), c(1.0),
					WaveData.load(getTestWavFile(440.0, 0.1)),
					WaveData.load(getTestWavFile(880.0, 0.1)));

			try (WaveOutput master = new WaveOutput(() -> null, 24, sampleRate, 1024, false)) {
				MultiChannelAudioOutput output = new MultiChannelAudioOutput(master);
				CellList result = mixdown.cells(cells, output, ChannelInfo.StereoChannel.RIGHT);
				Assert.assertNotNull("EFX graph should build against a mono (null-master) " +
						"destination without throwing", result);
			}
		} finally {
			restoreFlags.run();
		}
	}

	/**
	 * Same as {@link #efxRoutingWithMonoOutput()}, but with {@code disableClean}
	 * enabled so the {@code createEfx} branch that delivers directly to the master
	 * and measure receptors (bypassing the clean main mix) is also exercised
	 * against a mono, null-master destination.
	 */
	@Test(timeout = 60000)
	@TestDepth(1)
	public void efxRoutingWithMonoOutputAndDisableClean() throws IOException {
		Runnable restoreFlags = captureFlags();
		try {
			MixdownManager.enableMainFilterUp = true;
			MixdownManager.enableEfxFilters = true;
			MixdownManager.enableEfx = true;
			MixdownManager.enableReverb = true;
			MixdownManager.enableTransmission = false;
			MixdownManager.enableWetInAdjustment = true;
			MixdownManager.enableMasterFilterDown = true;
			MixdownManager.disableClean = true;
			MixdownManager.enableSourcesOnly = false;

			GlobalTimeManager time = new GlobalTimeManager(measure -> 0);
			int params = 8;
			ProjectedGenome genome = new ProjectedGenome(params);
			MixdownManager mixdown = mono(time, genome, params);

			CellList cells = w(0, c(0.0), c(1.0),
					WaveData.load(getTestWavFile(440.0, 0.1)),
					WaveData.load(getTestWavFile(880.0, 0.1)));

			try (WaveOutput master = new WaveOutput(() -> null, 24, sampleRate, 1024, false)) {
				MultiChannelAudioOutput output = new MultiChannelAudioOutput(master);
				CellList result = mixdown.cells(cells, output, ChannelInfo.StereoChannel.RIGHT);
				Assert.assertNotNull("EFX graph should build against a mono (null-master) " +
						"destination without throwing, even with disableClean routing", result);
			}
		} finally {
			restoreFlags.run();
		}
	}
}
