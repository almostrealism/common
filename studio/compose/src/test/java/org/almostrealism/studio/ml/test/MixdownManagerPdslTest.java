/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the PDSL rendition of
 * {@link org.almostrealism.studio.arrange.MixdownManager}'s signal flow
 * (see {@code pdsl/audio/mixdown_manager.pdsl}).
 *
 * <p>The passing tests exercise the fixed-parameter rendition:
 * per-channel HP+volume bus, per-channel wet-filter+delay bus with
 * cross-channel routing, and a master LP. Every passing test also writes
 * a WAV file to {@code results/pdsl-audio-dsp/} for audible confirmation.</p>
 *
 * <p>The {@code @Ignore}-annotated tests document the capabilities that
 * {@code MixdownManager} requires but that PDSL does not yet express.
 * Each carries a reason string naming the missing capability and
 * cross-referencing the decomposition table in
 * {@code docs/plans/PDSL_AUDIO_DSP.md} Section 10. These tests are
 * written in their intended final form; when the missing capability
 * lands, each one should pass after removing only its {@code @Ignore}.</p>
 *
 * @see org.almostrealism.studio.arrange.MixdownManager
 * @see org.almostrealism.ml.dsl.PdslInterpreter
 */
public class MixdownManagerPdslTest extends TestSuiteBase implements FirFilterTestFeatures {

	/** Audio buffer size used across tests. */
	private static final int SIGNAL_SIZE = 256;

	/** Sample rate used across tests (Hz). */
	private static final int SAMPLE_RATE = 44100;

	/** Number of parallel pattern channels — matches the typical MixdownManager usage. */
	private static final int CHANNELS = 4;

	/** FIR filter order. */
	private static final int FILTER_ORDER = 40;

	/** Main-path high-pass cutoff (Hz). */
	private static final double HP_CUTOFF = 200.0;

	/** Master low-pass cutoff (Hz). */
	private static final double LP_CUTOFF = 8000.0;

	/** Wet EFX filter cutoff used by the per-channel FIR (Hz). */
	private static final double WET_LP_CUTOFF = 5000.0;

	/** Per-channel volume. */
	private static final double VOLUME = 1.0;

	/** Wet send level. */
	private static final double WET_LEVEL = 0.35;

	/** Integer delay per channel in samples (quarter-buffer offset). */
	private static final int DELAY_SAMPLES = 64;

	/**
	 * Number of forward passes used by the producer-args automation tests.
	 * Each pass is one buffer of {@link #SIGNAL_SIZE} samples; with the
	 * default value the tests render roughly half a second of audio per
	 * compiled model.
	 */
	private static final int NUM_AUTOMATION_PASSES = 86;

	/**
	 * Parses {@code mixdown_manager.pdsl} and verifies that all three layers
	 * ({@code mixdown_main_bus}, {@code mixdown_efx_bus}, {@code mixdown_master})
	 * are present.
	 */
	@Test(timeout = 30000)
	public void testMixdownManagerPdslParsesCorrectly() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		Assert.assertNotNull("Program must not be null", program);

		List<PdslNode.Definition> defs = program.getDefinitions();
		Assert.assertNotNull("Definitions must not be null", defs);

		boolean hasMain = false;
		boolean hasEfx = false;
		boolean hasMaster = false;
		for (PdslNode.Definition def : defs) {
			if (def instanceof PdslNode.LayerDef) {
				if ("mixdown_main_bus".equals(def.getName())) hasMain = true;
				if ("mixdown_efx_bus".equals(def.getName())) hasEfx = true;
				if ("mixdown_master".equals(def.getName())) hasMaster = true;
			}
		}
		Assert.assertTrue("mixdown_main_bus layer must be defined", hasMain);
		Assert.assertTrue("mixdown_efx_bus layer must be defined", hasEfx);
		Assert.assertTrue("mixdown_master layer must be defined", hasMaster);
	}

	/**
	 * Builds the {@code mixdown_main_bus} block and verifies it compiles
	 * and produces output of shape {@code [1, signal_size]} from a
	 * {@code [channels, signal_size]} input.
	 */
	@Test(timeout = 60000)
	public void testMixdownMainBusBuilds() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, SIGNAL_SIZE);

		Block block = loader.buildLayer(program, "mixdown_main_bus", inputShape, mainBusArgs());
		Assert.assertNotNull("mixdown_main_bus Block must not be null", block);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection output = compiled.forward(zeroInput(CHANNELS, SIGNAL_SIZE));
		Assert.assertEquals("Output must have SIGNAL_SIZE samples (mono)",
				SIGNAL_SIZE, output.getShape().getTotalSize());
	}

	/**
	 * Builds the {@code mixdown_efx_bus} block and verifies shape and non-null compile.
	 */
	@Test(timeout = 60000)
	public void testMixdownEfxBusBuilds() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, SIGNAL_SIZE);

		Block block = loader.buildLayer(program, "mixdown_efx_bus", inputShape, efxBusArgs());
		Assert.assertNotNull("mixdown_efx_bus Block must not be null", block);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection output = compiled.forward(zeroInput(CHANNELS, SIGNAL_SIZE));
		Assert.assertEquals("Output must have SIGNAL_SIZE samples (mono)",
				SIGNAL_SIZE, output.getShape().getTotalSize());
	}

	/**
	 * Builds the full {@code mixdown_master} block and verifies shape.
	 */
	@Test(timeout = 60000)
	public void testMixdownMasterBuilds() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, SIGNAL_SIZE);

		Block block = loader.buildLayer(program, "mixdown_master", inputShape, masterArgs());
		Assert.assertNotNull("mixdown_master Block must not be null", block);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection output = compiled.forward(zeroInput(CHANNELS, SIGNAL_SIZE));
		Assert.assertEquals("Output must have SIGNAL_SIZE samples (mono)",
				SIGNAL_SIZE, output.getShape().getTotalSize());
	}

	/**
	 * Verifies that {@code mixdown_main_bus} collapses the N channels correctly:
	 * feeding a signal with energy on only one of the N channels and zeroes on the
	 * rest should produce a non-zero mono output (the HP+volume applied to that one
	 * channel, then summed). Feeding a fully-zero signal should produce a fully-zero
	 * output.
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testMixdownMainBusCollapsesChannels() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, SIGNAL_SIZE);

		Block block = loader.buildLayer(program, "mixdown_main_bus", inputShape, mainBusArgs());
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		// Zero input → zero output
		PackedCollection zeroOut = compiled.forward(zeroInput(CHANNELS, SIGNAL_SIZE));
		double zeroEnergy = energy(zeroOut.toArray(0, SIGNAL_SIZE), FILTER_ORDER);
		Assert.assertEquals("Zero input must produce zero output", 0.0, zeroEnergy, 1e-9);

		// 1 kHz tone on channel 0 only → non-zero mono output (passband)
		PackedCollection sparse = sparseChannelInput(0, 1000.0);
		double[] sparseOut = compiled.forward(sparse).toArray(0, SIGNAL_SIZE);
		double sparseEnergy = energy(sparseOut, FILTER_ORDER);
		Assert.assertTrue(
				"Sparse channel-0 input must produce non-zero mono output via sum_channels: outEnergy=" + sparseEnergy,
				sparseEnergy > 0.0);
	}

	/**
	 * Verifies that {@code mixdown_efx_bus} is shape-correct and that a non-zero
	 * input produces a non-zero output — the per-channel wet FIR, scale, delay,
	 * and cross-channel routing are all active.
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testMixdownEfxBusProducesOutput() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, SIGNAL_SIZE);

		Block block = loader.buildLayer(program, "mixdown_efx_bus", inputShape, efxBusArgs());
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		// 440 Hz tone on all channels → non-zero wet output
		PackedCollection input = multiChannelTone(440.0);
		double[] out = compiled.forward(input).toArray(0, SIGNAL_SIZE);
		double outEnergy = energy(out, FILTER_ORDER);
		Assert.assertTrue(
				"efx_bus on all-channels 440 Hz tone must produce non-zero output: outEnergy=" + outEnergy,
				outEnergy > 0.0);
	}

	/**
	 * Renders 1 second of audio through {@code mixdown_master} and writes WAV
	 * files to {@code results/pdsl-audio-dsp/}. This is the audible proof that
	 * the PDSL-rendered MixdownManager signal flow works end-to-end.
	 *
	 * <p>Also verifies that the master output differs from the dry input —
	 * the LP filter, per-channel HP, delay, and cross-channel routing all
	 * contribute, so the mono output cannot equal the simple sum of inputs.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testMixdownMasterWritesWav() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, SIGNAL_SIZE);

		Block block = loader.buildLayer(program, "mixdown_master", inputShape, masterArgs());
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		int totalSamples = SAMPLE_RATE;
		int numPasses = totalSamples / SIGNAL_SIZE;
		float[] dryMono = new float[totalSamples];
		float[] masterMono = new float[totalSamples];

		// Per-channel carrier frequencies: one tone per channel
		double[] channelFreqs = {220.0, 440.0, 880.0, 1760.0};

		for (int pass = 0; pass < numPasses; pass++) {
			final int sampleOffset = pass * SIGNAL_SIZE;
			PackedCollection input = multiChannelCarrier(channelFreqs, sampleOffset);
			double[] inArr = input.toArray(0, CHANNELS * SIGNAL_SIZE);
			double[] outArr = compiled.forward(input).toArray(0, SIGNAL_SIZE);

			// Summed dry (reference: what a naive pre-effects sum would produce)
			for (int i = 0; i < SIGNAL_SIZE; i++) {
				double sum = 0.0;
				for (int c = 0; c < CHANNELS; c++) {
					sum += inArr[c * SIGNAL_SIZE + i];
				}
				dryMono[sampleOffset + i] = (float) sum;
				masterMono[sampleOffset + i] = (float) outArr[i];
			}
		}

		PdslAudioDemoTest.writeDemoWav(
				new File(outputDir, "pdsl_mixdown_manager_dry.wav"), dryMono, SAMPLE_RATE);
		PdslAudioDemoTest.writeDemoWav(
				new File(outputDir, "pdsl_mixdown_manager_master.wav"), masterMono, SAMPLE_RATE);

		Assert.assertTrue("Dry WAV must be non-empty",
				new File(outputDir, "pdsl_mixdown_manager_dry.wav").length() > 0);
		Assert.assertTrue("Master WAV must be non-empty",
				new File(outputDir, "pdsl_mixdown_manager_master.wav").length() > 0);

		// The master output must differ from the naive dry sum — filters, delay,
		// and routing must all be active.
		double diffEnergy = 0.0;
		for (int i = 0; i < totalSamples; i++) {
			double diff = masterMono[i] - dryMono[i];
			diffEnergy += diff * diff;
		}
		Assert.assertTrue(
				"master output must differ from naive dry sum (HP+LP+delay+route must be active)",
				diffEnergy > 0.0);
	}

	// ====================================================================
	// @Ignore tests — capabilities currently not in PDSL.
	// See docs/plans/PDSL_AUDIO_DSP.md Section 11 for the index and
	// estimated implementation cost.
	// ====================================================================

	/**
	 * Exercises a PDSL {@code mixdown_main_bus} whose per-channel HP cutoff is
	 * a time-varying scalar {@link Producer} (a 1-element {@link PackedCollection}
	 * slot mutated between forward passes) rather than a compile-time constant.
	 * Mirrors {@code MixdownManager.createCells()} lines 504-521 (Section 10 rows 1-2):
	 * the production path drives the cutoff via
	 * {@code AutomationManager.getAggregatedValue(...)} which returns a
	 * shape-{@code [1]} {@code Producer<PackedCollection>}; this test mocks that
	 * shape with a synthetic mutable slot for substrate isolation.
	 *
	 * <p>The mutable slot sweeps the cutoff from 100 Hz upward, traversing the
	 * carrier frequencies of the dry tones, so the frequency-by-frequency
	 * roll-off changes pass-by-pass. The corresponding fixed-cutoff layer
	 * (built with the slot's mean cutoff) is rendered through the same input
	 * sequence; the producer-driven output must differ audibly from this
	 * baseline — a regression where the producer is silently treated as a
	 * constant would make {@code diffEnergy} collapse to floating-point noise.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testMixdownManagerAutomatedHighpass() throws IOException {
		PackedCollection cutoffSlot = new PackedCollection(1);
		double[] schedule = sweepSchedule(100.0, 4500.0, NUM_AUTOMATION_PASSES);

		Map<String, Object> producerArgs = mainBusArgs();
		producerArgs.put("hp_cutoff", cutoffSlot);

		Map<String, Object> constArgs = mainBusArgs();
		constArgs.put("hp_cutoff", scheduleMean(schedule));

		AutomationResult result = renderAutomatedComparison(
				"mixdown_main_bus", producerArgs, constArgs, cutoffSlot, schedule,
				"mixdown_manager_automated_hp.wav");

		Assert.assertTrue(
				"automated HP cutoff must differ from constant-cutoff baseline: "
						+ "diffEnergy=" + result.diffEnergy + ", baselineEnergy=" + result.baselineEnergy,
				result.diffEnergy > 0.05 * Math.max(result.baselineEnergy, 1.0e-9));
	}

	/**
	 * Exercises a PDSL {@code mixdown_main_bus} whose per-channel volume is a
	 * time-varying scalar {@link Producer}. Mirrors
	 * {@code MixdownManager.createCells()} line 524-526 (Section 10 row 3):
	 * the production path drives the volume via
	 * {@code AutomationManager.getAggregatedValue(...)}; this test mocks that
	 * shape with a mutable slot.
	 *
	 * <p>The schedule fades from {@code 0.0} to {@code 1.0} so the producer
	 * version is a fade-in while the constant baseline holds at the mean
	 * (~0.5). The two outputs must therefore differ measurably even before
	 * filtering effects are considered.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testMixdownManagerAutomatedVolume() throws IOException {
		PackedCollection volumeSlot = new PackedCollection(1);
		double[] schedule = sweepSchedule(0.0, 1.0, NUM_AUTOMATION_PASSES);

		Map<String, Object> producerArgs = mainBusArgs();
		producerArgs.put("volume", volumeSlot);

		Map<String, Object> constArgs = mainBusArgs();
		constArgs.put("volume", scheduleMean(schedule));

		AutomationResult result = renderAutomatedComparison(
				"mixdown_main_bus", producerArgs, constArgs, volumeSlot, schedule,
				"mixdown_manager_automated_volume.wav");

		Assert.assertTrue(
				"automated volume must differ from constant-volume baseline: "
						+ "diffEnergy=" + result.diffEnergy + ", baselineEnergy=" + result.baselineEnergy,
				result.diffEnergy > 0.05 * Math.max(result.baselineEnergy, 1.0e-9));
	}

	/**
	 * Exercises a PDSL {@code mixdown_master} whose master LP cutoff is a
	 * time-varying scalar {@link Producer}. Mirrors
	 * {@code MixdownManager.createEfx()} line 707-714 (Section 10 row 28): the
	 * production path drives the master filter-down via
	 * {@code AutomationManager.getAggregatedValue(...)}; this test mocks that
	 * shape with a mutable slot.
	 *
	 * <p>The schedule sweeps the LP cutoff from 8 kHz down to 500 Hz —
	 * progressively rolling off the higher dry-tone carriers — while the
	 * constant baseline runs at the mean cutoff. State-bearing components in
	 * the rest of the chain (per-channel delay buffers/heads) are allocated
	 * separately for each compiled model so the only divergence between the
	 * two outputs is the LP behaviour.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testMixdownManagerAutomatedLowpass() throws IOException {
		PackedCollection lpSlot = new PackedCollection(1);
		double[] schedule = sweepSchedule(8000.0, 500.0, NUM_AUTOMATION_PASSES);

		Map<String, Object> producerArgs = masterArgs();
		producerArgs.put("lp_cutoff", lpSlot);

		Map<String, Object> constArgs = masterArgs();
		constArgs.put("lp_cutoff", scheduleMean(schedule));

		AutomationResult result = renderAutomatedComparison(
				"mixdown_master", producerArgs, constArgs, lpSlot, schedule,
				"mixdown_manager_automated_lp.wav");

		Assert.assertTrue(
				"automated master LP must differ from constant-cutoff baseline: "
						+ "diffEnergy=" + result.diffEnergy + ", baselineEnergy=" + result.baselineEnergy,
				result.diffEnergy > 0.05 * Math.max(result.baselineEnergy, 1.0e-9));
	}

	/**
	 * Exercises a PDSL {@code mixdown_efx_bus} whose per-channel delay length
	 * is a time-varying scalar {@link Producer}. Mirrors
	 * {@code MixdownManager.createEfx()} line 654-658 (Section 10 row 17): the
	 * production path uses {@code AdjustableDelayCell} whose delay is driven
	 * by a polycyclic gene scaled by the audio clock.
	 *
	 * <p>The schedule sweeps the delay length across {@code [16, 192]} samples
	 * (well below {@code SIGNAL_SIZE} so the buffer-bound invariant holds).
	 * The constant baseline runs at the mean delay. Because the per-channel
	 * delay buffers are stateful, the two compiled models receive their own
	 * buffer/head allocations via separate {@code efxBusArgs()} calls.</p>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testMixdownManagerVariableDelayTime() throws IOException {
		PackedCollection delaySlot = new PackedCollection(1);
		double[] schedule = sweepSchedule(16.0, 192.0, NUM_AUTOMATION_PASSES);

		Map<String, Object> producerArgs = efxBusArgs();
		producerArgs.put("delay_samples", delaySlot);

		Map<String, Object> constArgs = efxBusArgs();
		constArgs.put("delay_samples", scheduleMean(schedule));

		AutomationResult result = renderAutomatedComparison(
				"mixdown_efx_bus", producerArgs, constArgs, delaySlot, schedule,
				"mixdown_manager_variable_delay.wav");

		Assert.assertTrue(
				"variable delay length must differ from constant-delay baseline: "
						+ "diffEnergy=" + result.diffEnergy + ", baselineEnergy=" + result.baselineEnergy,
				result.diffEnergy > 0.05 * Math.max(result.baselineEnergy, 1.0e-9));
	}

	/**
	 * When Capability C (rectangular routing) lands, verify that a PDSL
	 * {@code route(matrix)} with a rectangular {@code [rows, cols]} matrix
	 * (N ≠ M) works — equivalent to {@code CellFeatures.m(fi(), delays, tg)}
	 * at {@code MixdownManager.createEfx()} line 664 (Section 10 rows 19-20).
	 */
	@Test(timeout = 60000)
	@Ignore("PDSL-blocked-by-rectangular-route: route(matrix) must support "
			+ "rectangular matrices [rows, cols] with rows != cols. Current "
			+ "MultiChannelDspFeatures.routeBlock is square-only. "
			+ "See PDSL_AUDIO_DSP.md Section 11.3 (Capability C — rectangular routing)."
			+ " Targets Section 10 rows 19-20 (MixdownManager.java:660-664).")
	public void testMixdownManagerRectangularRoute() {
		throw new AssertionError(
				"Placeholder: when rectangular route lands, verify an N-channel efx bus "
						+ "fanning into M ≠ N delay layers.");
	}

	/**
	 * When Capability D (heterogeneous fan-out) lands, verify that a PDSL
	 * construct can apply a *different* sub-block to each branch of a fan-out —
	 * equivalent to {@code CellList.branch(IntFunction<Cell>...)} at
	 * {@code MixdownManager.createCells()} lines 572-578 and 592-601
	 * (Section 10 rows 10-11).
	 */
	@Test(timeout = 60000)
	@Ignore("PDSL-blocked-by-heterogeneous-fanout: no PDSL primitive today "
			+ "applies a different sub-block to each branch output. concat_blocks "
			+ "concatenates, fan_out replicates identically. "
			+ "See PDSL_AUDIO_DSP.md Section 11.4 (Capability D — heterogeneous fan-out)."
			+ " Targets Section 10 rows 10-11 (MixdownManager.java:572-602).")
	public void testMixdownManagerHeterogeneousBranch() {
		throw new AssertionError(
				"Placeholder: when heterogeneous fan-out lands, branch the main signal "
						+ "into {volume, volume·wet_filter, reverb_factor} with a single "
						+ "PDSL construct.");
	}

	/**
	 * When Capability E ({@code delay_network}) lands AND Capability A
	 * (automation) lands, verify that the PDSL reverb bus can be expressed —
	 * equivalent to {@code reverb.sum().map(DelayNetwork)} at
	 * {@code MixdownManager.createEfx()} lines 672-683 (Section 10 rows 9, 24, 25).
	 */
	@Test(timeout = 60000)
	@Ignore("PDSL-blocked-by-DelayNetwork: no PDSL primitive equivalent to "
			+ "org.almostrealism.audio.filter.DelayNetwork (multi-tap feedback "
			+ "reverb). Also needs Capability A for the per-channel reverb wet factor. "
			+ "See PDSL_AUDIO_DSP.md Section 11.5 (Capability E — delay_network(...))."
			+ " Targets Section 10 rows 9, 24, 25 (MixdownManager.java:546-561, 672-683).")
	public void testMixdownManagerReverbPath() {
		throw new AssertionError(
				"Placeholder: when delay_network(...) lands, build a reverb bus and "
						+ "compare against the Java DelayNetwork reference.");
	}

	// ==== Helpers ====

	/**
	 * Zero-initialised input of shape {@code [channels, signalSize]}.
	 */
	private PackedCollection zeroInput(int channels, int signalSize) {
		PackedCollection input = new PackedCollection(
				new TraversalPolicy(channels, signalSize));
		input.setMem(new double[channels * signalSize]);
		return input;
	}

	/**
	 * Input of shape {@code [channels, signalSize]} with a sine tone on one channel
	 * and zeros on the rest.
	 */
	private PackedCollection sparseChannelInput(int activeChannel, double freqHz) {
		double[] data = new double[CHANNELS * SIGNAL_SIZE];
		for (int i = 0; i < SIGNAL_SIZE; i++) {
			double t = (double) i / SAMPLE_RATE;
			data[activeChannel * SIGNAL_SIZE + i] =
					Math.sin(2.0 * Math.PI * freqHz * t);
		}
		PackedCollection input = new PackedCollection(
				new TraversalPolicy(CHANNELS, SIGNAL_SIZE));
		input.setMem(data);
		return input;
	}

	/**
	 * Input of shape {@code [channels, signalSize]} with the same sine tone on
	 * every channel (uniform multi-channel signal).
	 */
	private PackedCollection multiChannelTone(double freqHz) {
		double[] data = new double[CHANNELS * SIGNAL_SIZE];
		for (int c = 0; c < CHANNELS; c++) {
			for (int i = 0; i < SIGNAL_SIZE; i++) {
				double t = (double) i / SAMPLE_RATE;
				data[c * SIGNAL_SIZE + i] = Math.sin(2.0 * Math.PI * freqHz * t);
			}
		}
		PackedCollection input = new PackedCollection(
				new TraversalPolicy(CHANNELS, SIGNAL_SIZE));
		input.setMem(data);
		return input;
	}

	/**
	 * Input of shape {@code [channels, signalSize]} with a distinct sine frequency
	 * per channel, starting at the given sample offset for continuous-phase
	 * playback across buffer boundaries.
	 */
	private PackedCollection multiChannelCarrier(double[] freqs, int sampleOffset) {
		double[] data = new double[CHANNELS * SIGNAL_SIZE];
		for (int c = 0; c < CHANNELS; c++) {
			double freq = freqs[c];
			for (int i = 0; i < SIGNAL_SIZE; i++) {
				double t = (double) (sampleOffset + i) / SAMPLE_RATE;
				data[c * SIGNAL_SIZE + i] =
						(1.0 / CHANNELS) * Math.sin(2.0 * Math.PI * freq * t);
			}
		}
		PackedCollection input = new PackedCollection(
				new TraversalPolicy(CHANNELS, SIGNAL_SIZE));
		input.setMem(data);
		return input;
	}

	/** Argument map for {@code mixdown_main_bus}. */
	private Map<String, Object> mainBusArgs() {
		Map<String, Object> args = new HashMap<>();
		args.put("channels", CHANNELS);
		args.put("signal_size", SIGNAL_SIZE);
		args.put("hp_cutoff", HP_CUTOFF);
		args.put("volume", VOLUME);
		args.put("sample_rate", (double) SAMPLE_RATE);
		args.put("filter_order", (double) FILTER_ORDER);
		return args;
	}

	/** Argument map for {@code mixdown_efx_bus}, including per-channel state. */
	private Map<String, Object> efxBusArgs() {
		Map<String, Object> args = new HashMap<>();
		args.put("channels", CHANNELS);
		args.put("signal_size", SIGNAL_SIZE);
		args.put("wet_level", WET_LEVEL);
		args.put("delay_samples", DELAY_SAMPLES);
		args.put("wet_filter_coeffs", perChannelWetCoeffs());
		args.put("transmission", nearIdentityTransmission());
		// Per-channel delay state: total size = channels * signal_size for buffers,
		// channels for heads, matching the subscript-slicing convention in the PDSL.
		PackedCollection buffers = new PackedCollection(CHANNELS * SIGNAL_SIZE);
		buffers.setMem(new double[CHANNELS * SIGNAL_SIZE]);
		PackedCollection heads = new PackedCollection(CHANNELS);
		heads.setMem(new double[CHANNELS]);
		args.put("buffers", buffers);
		args.put("heads", heads);
		return args;
	}

	/** Argument map for {@code mixdown_master} — union of main bus and efx bus. */
	private Map<String, Object> masterArgs() {
		Map<String, Object> args = new HashMap<>();
		args.put("channels", CHANNELS);
		args.put("signal_size", SIGNAL_SIZE);
		args.put("hp_cutoff", HP_CUTOFF);
		args.put("volume", VOLUME);
		args.put("lp_cutoff", LP_CUTOFF);
		args.put("sample_rate", (double) SAMPLE_RATE);
		args.put("filter_order", (double) FILTER_ORDER);
		args.put("wet_level", WET_LEVEL);
		args.put("delay_samples", DELAY_SAMPLES);
		args.put("wet_filter_coeffs", perChannelWetCoeffs());
		args.put("transmission", nearIdentityTransmission());
		PackedCollection buffers = new PackedCollection(CHANNELS * SIGNAL_SIZE);
		buffers.setMem(new double[CHANNELS * SIGNAL_SIZE]);
		PackedCollection heads = new PackedCollection(CHANNELS);
		heads.setMem(new double[CHANNELS]);
		args.put("buffers", buffers);
		args.put("heads", heads);
		return args;
	}

	/**
	 * Builds a concatenated per-channel LP coefficient set of total size
	 * {@code channels * (filter_order + 1)}. The PDSL subscript carves one
	 * channel's coefficients via {@code wet_filter_coeffs[channel]} at build time.
	 */
	private PackedCollection perChannelWetCoeffs() {
		int perChannel = FILTER_ORDER + 1;
		double[] data = new double[CHANNELS * perChannel];
		double[] coeffs = referenceLowPassCoefficients(WET_LP_CUTOFF, SAMPLE_RATE, FILTER_ORDER);
		for (int c = 0; c < CHANNELS; c++) {
			System.arraycopy(coeffs, 0, data, c * perChannel, perChannel);
		}
		PackedCollection all = new PackedCollection(CHANNELS * perChannel);
		all.setMem(data);
		return all;
	}

	/**
	 * Result of a producer-args automation comparison: total
	 * sum-of-squared-differences between the producer-driven output and the
	 * constant-baseline output, plus the baseline output's own
	 * sum-of-squares for normalisation.
	 */
	private static final class AutomationResult {
		final double diffEnergy;
		final double baselineEnergy;

		AutomationResult(double diffEnergy, double baselineEnergy) {
			this.diffEnergy = diffEnergy;
			this.baselineEnergy = baselineEnergy;
		}
	}

	/**
	 * Builds a linear schedule that ramps from {@code start} to {@code end}
	 * across {@code numPasses} entries. Used to drive the producer-valued
	 * scalar slot from one forward pass to the next.
	 */
	private double[] sweepSchedule(double start, double end, int numPasses) {
		double[] schedule = new double[numPasses];
		if (numPasses == 1) {
			schedule[0] = 0.5 * (start + end);
			return schedule;
		}
		for (int i = 0; i < numPasses; i++) {
			double t = (double) i / (numPasses - 1);
			schedule[i] = start + t * (end - start);
		}
		return schedule;
	}

	/** Arithmetic mean of a schedule, used as the constant baseline. */
	private double scheduleMean(double[] schedule) {
		double sum = 0.0;
		for (double v : schedule) sum += v;
		return sum / schedule.length;
	}

	/**
	 * Renders {@code numPasses} of audio through two compiled models — one
	 * built with the producer-valued slot, one with the slot's mean as a
	 * Number literal — feeding both with identical multi-channel carrier
	 * input. Mutates the producer slot to {@code schedule[pass]} before each
	 * pass so the kernel reads the current value. Writes the producer-driven
	 * output as a WAV file and asserts the WAV is non-empty before returning
	 * the energy comparison.
	 */
	private AutomationResult renderAutomatedComparison(String layerName,
													   Map<String, Object> producerArgs,
													   Map<String, Object> constArgs,
													   PackedCollection slot,
													   double[] schedule,
													   String wavFileName) throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, SIGNAL_SIZE);

		Block producerBlock = loader.buildLayer(program, layerName, inputShape, producerArgs);
		Block constBlock = loader.buildLayer(program, layerName, inputShape, constArgs);

		Model producerModel = new Model(inputShape);
		producerModel.add(producerBlock);
		CompiledModel producerCompiled = producerModel.compile();

		Model constModel = new Model(inputShape);
		constModel.add(constBlock);
		CompiledModel constCompiled = constModel.compile();

		int numPasses = schedule.length;
		int totalSamples = numPasses * SIGNAL_SIZE;
		float[] producerMono = new float[totalSamples];
		float[] constMono = new float[totalSamples];

		double[] channelFreqs = {220.0, 440.0, 880.0, 1760.0};

		for (int pass = 0; pass < numPasses; pass++) {
			int sampleOffset = pass * SIGNAL_SIZE;
			slot.setMem(0, schedule[pass]);

			double[] producerOut = producerCompiled
					.forward(multiChannelCarrier(channelFreqs, sampleOffset))
					.toArray(0, SIGNAL_SIZE);
			double[] constOut = constCompiled
					.forward(multiChannelCarrier(channelFreqs, sampleOffset))
					.toArray(0, SIGNAL_SIZE);

			for (int i = 0; i < SIGNAL_SIZE; i++) {
				producerMono[sampleOffset + i] = (float) producerOut[i];
				constMono[sampleOffset + i] = (float) constOut[i];
			}
		}

		File wav = new File(outputDir, wavFileName);
		PdslAudioDemoTest.writeDemoWav(wav, producerMono, SAMPLE_RATE);
		Assert.assertTrue("Producer-driven WAV must be non-empty: " + wav, wav.length() > 0);

		double diffEnergy = 0.0;
		double baselineEnergy = 0.0;
		for (int i = 0; i < totalSamples; i++) {
			double diff = (double) producerMono[i] - (double) constMono[i];
			diffEnergy += diff * diff;
			baselineEnergy += (double) constMono[i] * (double) constMono[i];
		}
		return new AutomationResult(diffEnergy, baselineEnergy);
	}

	/**
	 * A near-identity {@code [channels, channels]} transmission matrix: each
	 * channel predominantly routes to itself with slight cross-channel bleed,
	 * rows summing to unity.
	 */
	private PackedCollection nearIdentityTransmission() {
		double[] data = new double[CHANNELS * CHANNELS];
		double main = 0.55;
		double bleed = (1.0 - main) / (CHANNELS - 1);
		for (int i = 0; i < CHANNELS; i++) {
			for (int j = 0; j < CHANNELS; j++) {
				data[i * CHANNELS + j] = (i == j) ? main : bleed;
			}
		}
		PackedCollection m = new PackedCollection(
				new TraversalPolicy(CHANNELS, CHANNELS));
		m.setMem(data);
		return m;
	}
}
