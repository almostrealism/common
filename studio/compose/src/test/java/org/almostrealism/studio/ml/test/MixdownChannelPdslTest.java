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
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the PDSL-defined mixdown channel pipeline ({@code mixdown_channel.pdsl}).
 *
 * <p>These tests verify that the {@code mixdown_main} and {@code mixdown_channel} PDSL
 * layers produce audio that exhibits the correct filtering behaviour: high-pass and
 * low-pass FIR filters bracket the signal, and the wet delay path adds echo.</p>
 *
 * <p>The PDSL layers use FIR approximations of the IIR biquad filters used by
 * {@link org.almostrealism.studio.arrange.MixdownManager}. Audible differences may
 * occur near the cutoff frequency. This is documented as an open issue in
 * {@code docs/plans/PDSL_AUDIO_DSP.md}.</p>
 *
 * <h2>What these tests verify</h2>
 * <ul>
 *   <li>The {@code mixdown_channel.pdsl} file parses without errors.</li>
 *   <li>Both {@code mixdown_main} and {@code mixdown_channel} layers build successfully.</li>
 *   <li>{@code mixdown_main}: high-pass attenuates below HP cutoff; low-pass attenuates above LP cutoff.</li>
 *   <li>{@code mixdown_channel}: wet delay path adds an echo contribution (output differs from main-path-only).</li>
 *   <li>WAV files are written to {@code results/pdsl-audio-dsp/} for human review.</li>
 * </ul>
 *
 * <h2>Deferred: MixdownManager comparison</h2>
 * <p>A direct sample-by-sample comparison against
 * {@link org.almostrealism.studio.arrange.MixdownManager} is deferred because:</p>
 * <ol>
 *   <li>MixdownManager uses IIR biquad filters; PDSL uses FIR — the impulse responses differ.</li>
 *   <li>MixdownManager requires a full genome / chromosome setup that is orthogonal to PDSL validation.</li>
 * </ol>
 * <p>The energy-level and echo-presence assertions below confirm correctness of the PDSL signal path
 * without requiring exact sample-level agreement.</p>
 *
 * @see org.almostrealism.ml.dsl.PdslInterpreter
 * @see org.almostrealism.studio.arrange.MixdownManager
 */
public class MixdownChannelPdslTest extends TestSuiteBase implements FirFilterTestFeatures {

	/** Audio buffer size used across tests. */
	private static final int SIGNAL_SIZE = 256;

	/** Sample rate used across tests (Hz). */
	private static final int SAMPLE_RATE = 44100;

	/** FIR filter order — matches EfxManager.filterOrder. */
	private static final int FILTER_ORDER = 40;

	/** High-pass cutoff for main path (Hz) — below this, energy should be attenuated. */
	private static final double HP_CUTOFF = 200.0;

	/** Low-pass cutoff for main path (Hz) — above this, energy should be attenuated. */
	private static final double LP_CUTOFF = 8000.0;

	/** Wet EFX filter cutoff for the channel wet path (Hz). */
	private static final double WET_LP_CUTOFF = 5000.0;

	/** Wet send level. */
	private static final double WET_LEVEL = 0.4;

	/** Volume gain applied in the main path. */
	private static final double VOLUME = 1.0;

	/**
	 * Parses {@code mixdown_channel.pdsl} and verifies that both {@code mixdown_main}
	 * and {@code mixdown_channel} layers are present in the program definitions.
	 */
	@Test(timeout = 30000)
	public void testMixdownChannelPdslParsesCorrectly() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_channel.pdsl");

		Assert.assertNotNull("Program must not be null", program);

		List<PdslNode.Definition> defs = program.getDefinitions();
		Assert.assertNotNull("Definitions must not be null", defs);
		Assert.assertFalse("Definitions must not be empty", defs.isEmpty());

		boolean hasMain = false;
		boolean hasChannel = false;
		for (PdslNode.Definition def : defs) {
			if (def instanceof PdslNode.LayerDef) {
				if ("mixdown_main".equals(def.getName())) hasMain = true;
				if ("mixdown_channel".equals(def.getName())) hasChannel = true;
			}
		}
		Assert.assertTrue("mixdown_main layer must be defined", hasMain);
		Assert.assertTrue("mixdown_channel layer must be defined", hasChannel);
	}

	/**
	 * Builds the {@code mixdown_main} block and verifies it does not throw.
	 */
	@Test(timeout = 30000)
	public void testMixdownMainBlockBuilds() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_channel.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		Map<String, Object> args = mainArgs();
		Block block = loader.buildLayer(program, "mixdown_main", inputShape, args);
		Assert.assertNotNull("mixdown_main Block must not be null", block);
	}

	/**
	 * Builds the {@code mixdown_channel} block (with wet delay state) and verifies it does not throw.
	 */
	@Test(timeout = 30000)
	public void testMixdownChannelBlockBuilds() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_channel.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		int delaySamples = SIGNAL_SIZE / 4;
		Map<String, Object> args = channelArgs(delaySamples);
		Block block = loader.buildLayer(program, "mixdown_channel", inputShape, args);
		Assert.assertNotNull("mixdown_channel Block must not be null", block);
	}

	/**
	 * Processes a multitone signal through {@code mixdown_main} and verifies:
	 * <ul>
	 *   <li>A sub-HP-cutoff tone (50 Hz) has less energy in the output than in the input.</li>
	 *   <li>A sub-LP-cutoff tone (1 kHz, in the passband) passes through with similar energy.</li>
	 *   <li>An above-LP-cutoff tone (14 kHz) has less energy in the output than in the input.</li>
	 * </ul>
	 *
	 * <p>Note: FIR filters have a transition band. The assertions use generous thresholds
	 * to account for this. Exact equivalence with the IIR filters in MixdownManager is
	 * not expected — see class Javadoc for discussion.</p>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testMixdownMainFilters() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_channel.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		Block block = loader.buildLayer(program, "mixdown_main", inputShape, mainArgs());
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		int totalSamples = SAMPLE_RATE;
		int numPasses = totalSamples / SIGNAL_SIZE;

		// Measure energy of each tone before and after filtering
		double[] inLow = new double[totalSamples];
		double[] inMid = new double[totalSamples];
		double[] inHigh = new double[totalSamples];
		double[] outLow = new double[totalSamples];
		double[] outMid = new double[totalSamples];
		double[] outHigh = new double[totalSamples];

		for (int pass = 0; pass < numPasses; pass++) {
			int offset = pass * SIGNAL_SIZE;

			// Low tone at 50 Hz (below HP cutoff — should be attenuated)
			PackedCollection lowInput = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (offset + i) / SAMPLE_RATE;
				return Math.sin(2.0 * Math.PI * 50.0 * t);
			});
			PackedCollection lowOutput = compiled.forward(lowInput);

			// Mid tone at 1 kHz (in passband — should pass through near unity)
			PackedCollection midInput = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (offset + i) / SAMPLE_RATE;
				return Math.sin(2.0 * Math.PI * 1000.0 * t);
			});
			PackedCollection midOutput = compiled.forward(midInput);

			// High tone at 14 kHz (above LP cutoff — should be attenuated)
			PackedCollection highInput = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (offset + i) / SAMPLE_RATE;
				return Math.sin(2.0 * Math.PI * 14000.0 * t);
			});
			PackedCollection highOutput = compiled.forward(highInput);

			double[] lo = lowInput.toArray(0, SIGNAL_SIZE);
			double[] loOut = lowOutput.toArray(0, SIGNAL_SIZE);
			double[] mi = midInput.toArray(0, SIGNAL_SIZE);
			double[] miOut = midOutput.toArray(0, SIGNAL_SIZE);
			double[] hi = highInput.toArray(0, SIGNAL_SIZE);
			double[] hiOut = highOutput.toArray(0, SIGNAL_SIZE);

			for (int i = 0; i < SIGNAL_SIZE; i++) {
				inLow[offset + i] = lo[i];
				outLow[offset + i] = loOut[i];
				inMid[offset + i] = mi[i];
				outMid[offset + i] = miOut[i];
				inHigh[offset + i] = hi[i];
				outHigh[offset + i] = hiOut[i];
			}
		}

		// Skip FIR edge effects when computing energy
		int skipEdge = FILTER_ORDER;
		double energyInLow = energy(inLow, skipEdge);
		double energyOutLow = energy(outLow, skipEdge);
		double energyInMid = energy(inMid, skipEdge);
		double energyOutMid = energy(outMid, skipEdge);
		double energyInHigh = energy(inHigh, skipEdge);
		double energyOutHigh = energy(outHigh, skipEdge);

		// HP filter: 50 Hz tone must be attenuated below its input energy
		Assert.assertTrue(
				"HP filter must attenuate 50 Hz (below " + HP_CUTOFF + " Hz cutoff): "
						+ "inEnergy=" + energyInLow + " outEnergy=" + energyOutLow,
				energyOutLow < energyInLow * 0.5);

		// LP filter: 14 kHz tone must be attenuated below its input energy
		Assert.assertTrue(
				"LP filter must attenuate 14 kHz (above " + LP_CUTOFF + " Hz cutoff): "
						+ "inEnergy=" + energyInHigh + " outEnergy=" + energyOutHigh,
				energyOutHigh < energyInHigh * 0.5);

		// Passband: 1 kHz tone should pass through with reasonable energy retention
		Assert.assertTrue(
				"1 kHz passband tone must retain most energy: "
						+ "inEnergy=" + energyInMid + " outEnergy=" + energyOutMid,
				energyOutMid > energyInMid * 0.5);
	}

	/**
	 * Processes a 440 Hz sine through {@code mixdown_channel} and verifies that
	 * the output differs from {@code mixdown_main} alone, confirming that the wet
	 * delay path contributes echo energy.
	 *
	 * <p>The difference energy (channel - main) must be positive because the wet
	 * path adds a delayed, filtered, scaled copy of the signal via {@code accum_blocks}.</p>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testMixdownChannelWetDelayAddsEcho() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_channel.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		// Build mixdown_main (no wet path)
		Block mainBlock = loader.buildLayer(program, "mixdown_main", inputShape, mainArgs());
		Model mainModel = new Model(inputShape);
		mainModel.add(mainBlock);
		CompiledModel mainCompiled = mainModel.compile();

		// Build mixdown_channel (with wet delay)
		int delaySamples = SIGNAL_SIZE / 4;
		Block channelBlock = loader.buildLayer(program, "mixdown_channel", inputShape,
				channelArgs(delaySamples));
		Model channelModel = new Model(inputShape);
		channelModel.add(channelBlock);
		CompiledModel channelCompiled = channelModel.compile();

		int totalSamples = SAMPLE_RATE;
		int numPasses = totalSamples / SIGNAL_SIZE;
		float[] mainSignal = new float[totalSamples];
		float[] channelSignal = new float[totalSamples];

		for (int pass = 0; pass < numPasses; pass++) {
			int offset = pass * SIGNAL_SIZE;
			PackedCollection input = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (offset + i) / SAMPLE_RATE;
				return Math.sin(2.0 * Math.PI * 440.0 * t);
			});

			double[] mainOut = mainCompiled.forward(input).toArray(0, SIGNAL_SIZE);
			double[] chanOut = channelCompiled.forward(input).toArray(0, SIGNAL_SIZE);

			for (int i = 0; i < SIGNAL_SIZE; i++) {
				mainSignal[offset + i] = (float) mainOut[i];
				channelSignal[offset + i] = (float) chanOut[i];
			}
		}

		// The wet delay path must contribute echo energy — outputs must differ
		double diffEnergy = 0.0;
		for (int i = 0; i < totalSamples; i++) {
			double diff = channelSignal[i] - mainSignal[i];
			diffEnergy += diff * diff;
		}
		Assert.assertTrue(
				"mixdown_channel wet delay must add echo energy (diff from main-only must be > 0)",
				diffEnergy > 0.0);
	}

	/**
	 * Runs the full mixdown channel pipeline and writes WAV files to
	 * {@code results/pdsl-audio-dsp/} for human review.
	 *
	 * <p>Produces three files:
	 * <ol>
	 *   <li>{@code pdsl_mixdown_dry.wav} — the raw 440 Hz + 2 kHz + 12 kHz input signal</li>
	 *   <li>{@code pdsl_mixdown_main.wav} — after {@code mixdown_main} (HP + volume + LP)</li>
	 *   <li>{@code pdsl_mixdown_channel.wav} — after {@code mixdown_channel} (main path + wet delay)</li>
	 * </ol>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testMixdownChannelWritesWav() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_channel.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		Block mainBlock = loader.buildLayer(program, "mixdown_main", inputShape, mainArgs());
		Model mainModel = new Model(inputShape);
		mainModel.add(mainBlock);
		CompiledModel mainCompiled = mainModel.compile();

		int delaySamples = SIGNAL_SIZE / 4;
		Block channelBlock = loader.buildLayer(program, "mixdown_channel", inputShape,
				channelArgs(delaySamples));
		Model channelModel = new Model(inputShape);
		channelModel.add(channelBlock);
		CompiledModel channelCompiled = channelModel.compile();

		int totalSamples = SAMPLE_RATE;
		int numPasses = totalSamples / SIGNAL_SIZE;
		float[] drySignal = new float[totalSamples];
		float[] mainSignal = new float[totalSamples];
		float[] channelSignal = new float[totalSamples];

		for (int pass = 0; pass < numPasses; pass++) {
			int offset = pass * SIGNAL_SIZE;
			PackedCollection input = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (offset + i) / SAMPLE_RATE;
				return 0.33 * Math.sin(2.0 * Math.PI * 440.0 * t)
						+ 0.33 * Math.sin(2.0 * Math.PI * 2000.0 * t)
						+ 0.33 * Math.sin(2.0 * Math.PI * 12000.0 * t);
			});

			double[] inArr = input.toArray(0, SIGNAL_SIZE);
			double[] mainOut = mainCompiled.forward(input).toArray(0, SIGNAL_SIZE);
			double[] chanOut = channelCompiled.forward(input).toArray(0, SIGNAL_SIZE);

			for (int i = 0; i < SIGNAL_SIZE; i++) {
				drySignal[offset + i] = (float) inArr[i];
				mainSignal[offset + i] = (float) mainOut[i];
				channelSignal[offset + i] = (float) chanOut[i];
			}
		}

		PdslAudioDemoTest.writeDemoWav(new File(outputDir, "pdsl_mixdown_dry.wav"), drySignal, SAMPLE_RATE);
		PdslAudioDemoTest.writeDemoWav(new File(outputDir, "pdsl_mixdown_main.wav"), mainSignal, SAMPLE_RATE);
		PdslAudioDemoTest.writeDemoWav(new File(outputDir, "pdsl_mixdown_channel.wav"), channelSignal, SAMPLE_RATE);

		Assert.assertTrue("Dry WAV must be non-empty",
				new File(outputDir, "pdsl_mixdown_dry.wav").length() > 0);
		Assert.assertTrue("Main WAV must be non-empty",
				new File(outputDir, "pdsl_mixdown_main.wav").length() > 0);
		Assert.assertTrue("Channel WAV must be non-empty",
				new File(outputDir, "pdsl_mixdown_channel.wav").length() > 0);

		// Main path must attenuate the 12 kHz content (LP filter active)
		double dryEnergy = energy(floatToDouble(drySignal), FILTER_ORDER);
		double mainEnergy = energy(floatToDouble(mainSignal), FILTER_ORDER);
		Assert.assertTrue(
				"mixdown_main LP filter must attenuate multitone signal containing 12 kHz: "
						+ "dryEnergy=" + dryEnergy + " mainEnergy=" + mainEnergy,
				mainEnergy < dryEnergy * 0.9);
	}

	// ---- Helpers ----

	/**
	 * Returns the argument map for the {@code mixdown_main} layer using the test constants.
	 */
	private Map<String, Object> mainArgs() {
		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("hp_cutoff", HP_CUTOFF);
		args.put("volume", VOLUME);
		args.put("lp_cutoff", LP_CUTOFF);
		args.put("sample_rate", (double) SAMPLE_RATE);
		args.put("filter_order", (double) FILTER_ORDER);
		return args;
	}

	/**
	 * Returns the argument map for the {@code mixdown_channel} layer, including
	 * the wet delay state (buffer and head as zero-initialised PackedCollections).
	 *
	 * @param delaySamples integer delay in samples
	 */
	private Map<String, Object> channelArgs(int delaySamples) {
		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", SIGNAL_SIZE);
		args.put("hp_cutoff", HP_CUTOFF);
		args.put("volume", VOLUME);
		args.put("lp_cutoff", LP_CUTOFF);
		args.put("sample_rate", (double) SAMPLE_RATE);
		args.put("filter_order", (double) FILTER_ORDER);
		args.put("wet_level", WET_LEVEL);
		args.put("delay_samples", delaySamples);

		// Pre-compute LP coefficients for the wet EFX filter (simulates FixedFilterChromosome)
		double[] coeffs = referenceLowPassCoefficients(WET_LP_CUTOFF, SAMPLE_RATE, FILTER_ORDER);
		PackedCollection wetCoeffs = new PackedCollection(coeffs.length);
		wetCoeffs.setMem(coeffs);
		args.put("wet_filter_coeffs", wetCoeffs);

		// Delay state: buffer size must exactly match SIGNAL_SIZE (the reshape
		// in callDelay requires buffer.getShape().getSize() == shape.getSize()).
		PackedCollection buffer = new PackedCollection(SIGNAL_SIZE);
		buffer.setMem(new double[SIGNAL_SIZE]);
		PackedCollection head = new PackedCollection(1);
		head.setMem(new double[]{0.0});
		args.put("buffer", buffer);
		args.put("head", head);

		return args;
	}

}
