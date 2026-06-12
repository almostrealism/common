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
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.studio.dsl.audio.AudioDspPrimitives;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Forward-pass performance diagnostics for the PDSL mixdown layers, built standalone at
 * production scale (6 channels, 8192 frames) with synthetic collection arguments so the
 * timings are attributable to the compiled layer structure alone.
 *
 * <p>Three kinds of diagnostics:</p>
 * <ul>
 *   <li>{@link #mixdownLayerForwardTiming} — per-layer build and forward medians,
 *       bisecting the full {@code mixdown_master_wet} into its arms;</li>
 *   <li>{@link #reverbBusForwardProfile} — an {@link OperationProfileNode} capture over
 *       the reverb bus for ar-profile-analyzer;</li>
 *   <li>{@link #reverbBusForwardSoak} / {@link #masterWetForwardSoak} — sustained forward
 *       loops for JVM-level observation (thread dumps, JFR), which is where dispatch and
 *       completion-latch overhead shows up — the operation profile cannot see it.</li>
 * </ul>
 *
 * <p>The correctness counterpart is {@link MixdownManagerPdslVerificationTest}; both use
 * {@link MixdownPdslTestFeatures} so they exercise identical layer configurations.</p>
 */
public class MixdownLayerPerformanceTest extends TestSuiteBase
		implements MixdownPdslTestFeatures {

	/** Channel count for production-scale layer builds. */
	private static final int CHANNELS = 6;

	/** Samples per forward pass for production-scale layer builds. */
	private static final int SIGNAL_SIZE = 8192;

	/**
	 * Times the compiled forward pass of each mixdown layer in isolation, bisecting the
	 * full {@code mixdown_master_wet} forward into the per-arm constructs: the main-bus
	 * FIR chain, the efx bus (delay + route), the reverb {@code delay_network} at three
	 * ring lengths, and the full wet master.
	 *
	 * @throws IOException if a layer cannot be built
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void mixdownLayerForwardTiming() throws IOException {
		int taps = PDSL_FILTER_ORDER + 1;

		Map<String, Object> main = new HashMap<>();
		main.put("channels", CHANNELS);
		main.put("signal_size", SIGNAL_SIZE);
		main.put("hp_cutoff", new PackedCollection(CHANNELS).fill(0.0));
		main.put("volume", onesCollection(CHANNELS));
		main.put("sample_rate", (double) SAMPLE_RATE);
		main.put("filter_order", (double) PDSL_FILTER_ORDER);
		timeLayerForward("mixdown_main_bus", "mixdown_main_bus",
				new TraversalPolicy(CHANNELS, SIGNAL_SIZE), main);

		Map<String, Object> efx = new HashMap<>();
		efx.put("channels", CHANNELS);
		efx.put("signal_size", SIGNAL_SIZE);
		efx.put("fir_taps", taps);
		efx.put("wet_filter_coeffs", identityFirBank(CHANNELS, taps));
		efx.put("wet_level", WET_LEVEL);
		efx.put("delay_samples", 6500);
		efx.put("transmission", new PackedCollection(
				new TraversalPolicy(CHANNELS, CHANNELS)).fill(0.0));
		efx.put("buffers", new PackedCollection(CHANNELS * SIGNAL_SIZE).fill(0.0));
		efx.put("heads", new PackedCollection(CHANNELS).fill(0.0));
		timeLayerForward("mixdown_efx_bus", "mixdown_efx_bus",
				new TraversalPolicy(CHANNELS, SIGNAL_SIZE), efx);

		// The ring length distinguishes the one-frame whole-buffer overwrite from the
		// multi-frame slot update; 2 frames is the production REVERB_FRAMES.
		for (int frames : new int[] { 1, 2, 4 }) {
			timeLayerForward("mixdown_reverb_bus[frames=" + frames + "]",
					"mixdown_reverb_bus", new TraversalPolicy(1, SIGNAL_SIZE),
					reverbBusArgs(CHANNELS, SIGNAL_SIZE, frames));
		}

		timeLayerForward("mixdown_master_wet", "mixdown_master_wet",
				new TraversalPolicy(2 * CHANNELS, SIGNAL_SIZE),
				masterWetArgs(CHANNELS, SIGNAL_SIZE));
	}

	/**
	 * Captures an {@link OperationProfileNode} over the production-ring (2-frame) reverb
	 * bus alone — build plus a short forward window — for offline attribution with
	 * ar-profile-analyzer. The profile must be assigned before the layer is built,
	 * because operations bind to the active profile at compile time.
	 *
	 * @throws IOException if the layer cannot be built or the profile cannot be saved
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void reverbBusForwardProfile() throws IOException {
		int frames = 2;

		OperationProfileNode profile = new OperationProfileNode("reverb_bus_forward");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			timeLayerForward("mixdown_reverb_bus[profiled,frames=" + frames + "]",
					"mixdown_reverb_bus", new TraversalPolicy(1, SIGNAL_SIZE),
					reverbBusArgs(CHANNELS, SIGNAL_SIZE, frames));
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		File profileFile = new File("results/pdsl-cutover/reverb_bus_profile.xml");
		profileFile.getParentFile().mkdirs();
		profile.save(profileFile.getPath());
		log("Reverb bus profile written to " + profileFile.getAbsolutePath());
	}

	/**
	 * Runs a sustained loop of forward passes over the production-ring (2-frame) reverb
	 * bus so a JVM-level profiler can observe where the per-forward wall time goes.
	 *
	 * @throws IOException if the layer cannot be built
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void reverbBusForwardSoak() throws IOException {
		soakLayerForward("mixdown_reverb_bus", new TraversalPolicy(1, SIGNAL_SIZE),
				reverbBusArgs(CHANNELS, SIGNAL_SIZE, 2), 60);
	}

	/**
	 * Runs a sustained loop of forward passes over the full {@code mixdown_master_wet}
	 * model so thread dumps can attribute its remaining forward cost — specifically
	 * whether the per-channel {@code for each channel} chains still pay per-dispatch
	 * completion-latch overhead or the residual is genuine kernel time.
	 *
	 * @throws IOException if the layer cannot be built
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void masterWetForwardSoak() throws IOException {
		soakLayerForward("mixdown_master_wet",
				new TraversalPolicy(2 * CHANNELS, SIGNAL_SIZE),
				masterWetArgs(CHANNELS, SIGNAL_SIZE), 600);
	}

	/**
	 * Builds the full synthetic argument map for {@code mixdown_master_wet}: neutral
	 * efx/reverb entries plus identity main-arm coefficients, unity volume, and the
	 * production wet/delay/master constants.
	 *
	 * @param channels number of channels
	 * @param signal   samples per channel per forward pass
	 * @return the argument map
	 */
	private Map<String, Object> masterWetArgs(int channels, int signal) {
		int taps = PDSL_FILTER_ORDER + 1;
		Map<String, Object> wet = new HashMap<>(neutralEfxArgs(channels, signal));
		wet.put("channels", channels);
		wet.put("signal_size", signal);
		wet.put("fir_taps", taps);
		wet.put("sample_rate", (double) SAMPLE_RATE);
		wet.put("filter_order", (double) PDSL_FILTER_ORDER);
		wet.put("hp_coeffs", identityFirBank(channels, taps));
		wet.put("volume", onesCollection(channels));
		wet.put("lp_coeffs", identityFir(taps));
		wet.put("wet_filter_coeffs", identityFirBank(channels, taps));
		wet.put("wet_level", WET_LEVEL);
		wet.put("delay_samples", 6500);
		wet.put("master_gain", 0.5);
		wet.put("buffers", new PackedCollection(channels * signal).fill(0.0));
		wet.put("heads", new PackedCollection(channels).fill(0.0));
		return wet;
	}

	/**
	 * Builds and compiles the given mixdown layer, then logs its build time and the
	 * median/max of five timed forward passes (after two warm-ups).
	 *
	 * @param label      report label for the timing line
	 * @param layerName  the layer to build from {@code mixdown_manager.pdsl}
	 * @param inputShape model input shape
	 * @param args       layer arguments
	 * @throws IOException if the layer cannot be built
	 */
	private void timeLayerForward(String label, String layerName, TraversalPolicy inputShape,
								  Map<String, Object> args) throws IOException {
		long buildStart = System.nanoTime();
		CompiledModel compiled = compileLayer(layerName, inputShape, args);
		double buildMs = (System.nanoTime() - buildStart) / 1e6;

		PackedCollection input = new PackedCollection(inputShape);
		input.fill(0.1);

		for (int i = 0; i < 2; i++) {
			compiled.forward(input);
		}

		double[] forwardMs = new double[5];
		for (int i = 0; i < forwardMs.length; i++) {
			long start = System.nanoTime();
			compiled.forward(input);
			forwardMs[i] = (System.nanoTime() - start) / 1e6;
		}
		Arrays.sort(forwardMs);

		log(String.format(
				"layerTiming layer=%s input=%s buildMs=%.2f medianForwardMs=%.2f maxForwardMs=%.2f",
				label, inputShape, buildMs,
				forwardMs[forwardMs.length / 2], forwardMs[forwardMs.length - 1]));
	}

	/**
	 * Builds the given layer and runs a sustained forward loop, logging a start marker
	 * (for profiler attachment) and the per-forward average on completion.
	 *
	 * @param layerName  the layer to build from {@code mixdown_manager.pdsl}
	 * @param inputShape model input shape
	 * @param args       layer arguments
	 * @param forwards   number of forward passes in the soak window
	 * @throws IOException if the layer cannot be built
	 */
	private void soakLayerForward(String layerName, TraversalPolicy inputShape,
								  Map<String, Object> args, int forwards) throws IOException {
		CompiledModel compiled = compileLayer(layerName, inputShape, args);

		PackedCollection input = new PackedCollection(inputShape);
		input.fill(0.1);
		compiled.forward(input);

		log("soak starting layer=" + layerName + " forwards=" + forwards);
		long start = System.nanoTime();
		for (int i = 0; i < forwards; i++) {
			compiled.forward(input);
		}
		double totalMs = (System.nanoTime() - start) / 1e6;
		log(String.format("soak complete layer=%s forwards=%d totalMs=%.2f perForwardMs=%.2f",
				layerName, forwards, totalMs, totalMs / forwards));
	}

	/**
	 * Builds and compiles the given mixdown layer into a {@link CompiledModel}.
	 *
	 * @param layerName  the layer to build from {@code mixdown_manager.pdsl}
	 * @param inputShape model input shape
	 * @param args       layer arguments
	 * @return the compiled model
	 * @throws IOException if the layer cannot be built
	 */
	private CompiledModel compileLayer(String layerName, TraversalPolicy inputShape,
									   Map<String, Object> args) throws IOException {
		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		Block block = loader.buildLayer(program, layerName, inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		return model.compile();
	}
}
