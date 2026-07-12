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
import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.studio.dsl.audio.AudioDspPrimitives;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.studio.arrange.AutomationManager;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.arrange.MixdownManagerPdslAdapter;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

/**
 * Real-audio comparison between {@link MixdownManager#cells} (the production Java path)
 * and the PDSL {@code mixdown_master} layer rendered through
 * {@link MixdownManagerPdslAdapter#buildArgsMap}.
 *
 * <p>Both paths are configured with identical fixtures: the same constructed
 * {@link MixdownManager}, the same source genome, the same channel count, the
 * same delay length, the same wet level. Both paths are then asked to render
 * the same input and the resulting audio is compared by per-channel energy and
 * by sum-of-squared-difference relative to the Java baseline. WAV files are
 * written for both paths to {@code results/pdsl-audio-dsp/} so the comparison
 * is auditable rather than just numeric.</p>
 *
 * <p>The most load-bearing structural mismatches between the two paths are:
 * <ul>
 *   <li>Java's per-channel HP cutoff comes from a per-channel automation gene;
 *       PDSL applies one shared cutoff producer to every channel — the adapter
 *       samples channel 0's gene.</li>
 *   <li>Java's wet filter is an IIR {@code AudioPassFilter} chain (HP+LP);
 *       PDSL renders this as a static FIR low-pass coefficient bank derived
 *       from the chromosome's LP frequency.</li>
 *   <li>Java's reverb path through {@code DelayNetwork} is the deferred
 *       Capability E in Section 12.5; both paths are rendered with reverb
 *       disabled to keep the comparison meaningful.</li>
 * </ul>
 * The comparison tolerance is therefore deliberately loose — the test verifies
 * that both paths produce non-degenerate audio with similar energy bounds, not
 * sample-accurate equivalence. A clean failure with a description of the
 * divergence is the primary deliverable.</p>
 */
public class MixdownManagerPdslVerificationTest extends TestSuiteBase
		implements CellFeatures, AudioTestFeatures, FirFilterTestFeatures, LayerFeatures,
					MixdownPdslTestFeatures {

	/** Number of audio channels used for both paths. */
	private static final int CHANNELS = 2;

	/** Number of delay layers in the EFX bus. Matches CHANNELS for square routing. */
	private static final int DELAY_LAYERS = CHANNELS;

	/** Render duration in seconds. Short enough to keep CI runtime modest. */
	private static final double DURATION_SECONDS = 2.0;

	/** Total samples per channel that both paths render. */
	private static final int TOTAL_FRAMES = (int) (DURATION_SECONDS * SAMPLE_RATE);

	/** Samples per PDSL forward pass — must divide TOTAL_FRAMES evenly. */
	private static final int PDSL_SIGNAL_SIZE = SAMPLE_RATE / 16;

	/** Genome parameter count. Oversized so any chromosome layout fits. */
	private static final int GENOME_PARAMS = 256;

	/** Test-only PDSL layers (slot liveness probes and distinct-row routing regressions). */
	private static final String VERIFICATION_PDSL = "/pdsl/audio/test_mixdown_verification.pdsl";

	/** Source sine frequency for the deterministic test signal. */
	private static final double SOURCE_FREQ_BASE = 220.0;

	/** Duration of the audible looped-sample demo, long enough to hear automation + delay evolve. */
	private static final double DEMO_SECONDS = 24.0;

	/**
	 * Larger PDSL ring/buffer for the demo. The {@code delay} primitive's echo length is bounded
	 * by the ring size (= signal size), so a bigger buffer is needed for an audible delay tap.
	 */
	private static final int DEMO_SIGNAL_SIZE = 8192;

	/** Demo delay length (~147 ms at 44.1 kHz), under {@link #DEMO_SIGNAL_SIZE} — a clear slapback. */
	private static final int DEMO_DELAY_SAMPLES = 6500;

	/** Higher wet-bus level for the demo so the delayed signal is clearly audible against the dry. */
	private static final double DEMO_WET_LEVEL = 0.55;

	/**
	 * Feedback-comb echo delay (~300 ms at 44.1 kHz). Deliberately longer than
	 * {@link #DEMO_SIGNAL_SIZE} (one frame) so the multi-frame ring is exercised — a delay
	 * shorter than one frame cannot be expressed by the block-parallel feedback (it would be
	 * an intra-frame recurrence that cannot run as a single parallel kernel).
	 */
	private static final int COMB_DELAY_SAMPLES = 13230;

	/** Feedback-comb ring depth in frames; {@code bufSize = COMB_BUFFER_FRAMES * signal_size} must exceed the delay. */
	private static final int COMB_BUFFER_FRAMES = 4;

	/** Feedback-comb regeneration gain ({@code transmission[[g]]}); &lt; 1 for a decaying echo train. */
	private static final double COMB_FEEDBACK_GAIN = 0.55;

	/** Feedback-comb output level ({@code passthrough[[wet]]}). */
	private static final double COMB_WET_LEVEL = 1.0;

	/** Channel count for the M×M feedback-grid demo (the cross-channel mixdown reverb structure). */
	private static final int GRID_CHANNELS = 4;

	/** Grid feedback gain; the scaled-Householder transmission matrix has spectral radius = this, so &lt; 1 is a stable decaying tail. */
	private static final double GRID_FEEDBACK_GAIN = 0.7;

	/** Grid output level (diagonal {@code passthrough}). */
	private static final double GRID_WET_LEVEL = 0.5;

	/** Automation manager of the most recently built fixture (see {@link #buildFixtureMixdown()}). */
	private AutomationManager fixtureAutomation;

	/** Time manager of the most recently built fixture (see {@link #buildFixtureMixdown()}). */
	private GlobalTimeManager fixtureTime;

	/**
	 * Builds the shared test fixture: the standard MixdownManager feature-flag configuration
	 * plus a populated {@link MixdownManager} (reverb disabled to keep the Java/PDSL comparison
	 * meaningful). The fixture's {@link AutomationManager} and {@link GlobalTimeManager} are
	 * exposed via {@link #fixtureAutomation} / {@link #fixtureTime} for callers that drive the
	 * Java path.
	 *
	 * @return the constructed mixdown manager
	 */
	private MixdownManager buildFixtureMixdown() {
		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		MixdownManager.enableTransmission = true;
		MixdownManager.enableWetInAdjustment = false;
		MixdownManager.enableMasterFilterDown = true;
		MixdownManager.enableMixdown = false;
		MixdownManager.enableSourcesOnly = false;
		MixdownManager.disableClean = false;
		MixdownManager.enableAutomationManager = true;
		MixdownManager.enableRiser = false;

		double measureDuration = Frequency.forBPM(120).l(4);
		fixtureTime = new GlobalTimeManager(
				measure -> (int) (measure * measureDuration * SAMPLE_RATE));
		ProjectedGenome genome = new ProjectedGenome(GENOME_PARAMS);
		fixtureAutomation = new AutomationManager(
				genome.addChromosome(), fixtureTime.getClock(),
				() -> measureDuration, SAMPLE_RATE);
		MixdownManager mixdown = new MixdownManager(genome.addChromosome(),
				CHANNELS, DELAY_LAYERS, fixtureAutomation, fixtureTime.getClock(), SAMPLE_RATE);
		genome.consolidateGeneValues();

		PackedCollection params = new PackedCollection(GENOME_PARAMS).fill(0.5);
		genome.assignTo(params);
		return mixdown;
	}

	/**
	 * Runs the manager setup ({@link AutomationManager#setup()}, {@link MixdownManager#setup()},
	 * {@link GlobalTimeManager#setup()}) for the most recently built fixture. The adapter's
	 * gene/automation producers read state that this setup initialises; without it they evaluate
	 * to non-finite values. {@link #compareJavaAndPdslMixdownPaths()} runs this implicitly as part
	 * of its Java-path setup, so only the PDSL-only demos need to call it.
	 *
	 * @param mixdown the fixture mixdown manager
	 */
	private void runFixtureSetup(MixdownManager mixdown) {
		OperationList setup = new OperationList("fixture setup");
		setup.add(fixtureAutomation.setup());
		setup.add(mixdown.setup());
		setup.add(fixtureTime.setup());
		setup.get().run();
	}

	/**
	 * Measures the broadband RMS gain of the FIR {@code highPass}/{@code lowPass} coefficients
	 * used by the PDSL {@code highpass}/{@code lowpass} primitives. The PDSL mixdown main arm
	 * applies a {@code highpass} per channel; if that FIR has a passband gain &gt; 1 it would
	 * inflate the dry mix relative to the Java resonant-IIR path. Logs gain at several cutoffs
	 * so a level divergence can be attributed to (or cleared of) the filter coefficients.
	 */
	@Test(timeout = 120_000)
	public void firFilterGainProbe() {
		int n = 16384;
		double[] s = new double[n];
		double inEnergy = 0.0;
		for (int i = 0; i < n; i++) {
			double t = i;
			double v = Math.sin(2 * Math.PI * 100 * t / 44100)
					+ Math.sin(2 * Math.PI * 1000 * t / 44100)
					+ Math.sin(2 * Math.PI * 6000 * t / 44100);
			s[i] = v;
			inEnergy += v * v;
		}
		double inRms = Math.sqrt(inEnergy / n);
		PackedCollection signal = new PackedCollection(n);
		signal.setMem(s);

		for (double cutoff : new double[] {50.0, 200.0, 1000.0, 5000.0}) {
			PackedCollection out = highPass(cp(signal), c(cutoff), 44100, 40).get().evaluate();
			log("highpass cutoff=" + cutoff + " order=40 inRms=" + inRms
					+ " outRms=" + rmsOf(out) + " gain=" + (rmsOf(out) / inRms));
		}
		for (double cutoff : new double[] {20000.0, 12000.0, 5000.0}) {
			PackedCollection out = lowPass(cp(signal), c(cutoff), 44100, 40).get().evaluate();
			log("lowpass cutoff=" + cutoff + " order=40 inRms=" + inRms
					+ " outRms=" + rmsOf(out) + " gain=" + (rmsOf(out) / inRms));
		}
	}

	/**
	 * Computes the RMS of a rendered collection.
	 *
	 * @param data the collection to measure
	 * @return the root-mean-square of all elements
	 */
	private double rmsOf(PackedCollection data) {
		double energy = 0.0;
		int len = data.getMemLength();
		for (int i = 0; i < len; i++) {
			double x = data.toDouble(i);
			energy += x * x;
		}
		return Math.sqrt(energy / Math.max(1, len));
	}

	/**
	 * Renders identical fixtures through the Java MixdownManager.cells() path
	 * and the PDSL mixdown_master layer, writes both WAVs, and asserts that
	 * neither path collapses to silence. The comparison is documented as
	 * structurally lossy (see class javadoc), so the assertion is loose.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void compareJavaAndPdslMixdownPaths() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		MixdownManager mixdown = buildFixtureMixdown();
		AutomationManager automation = fixtureAutomation;
		GlobalTimeManager time = fixtureTime;

		File javaWav = new File(outputDir, "mixdown_manager_java_path.wav");
		File pdslWav = new File(outputDir, "mixdown_manager_pdsl_path.wav");
		File diffWav = new File(outputDir, "mixdown_manager_diff.wav");

		double[] javaSamples = renderJavaPath(mixdown, automation, time, javaWav);
		double[] pdslSamples = renderPdslPath(mixdown, pdslWav);

		double javaEnergy = energy(javaSamples, 0);
		double pdslEnergy = energy(pdslSamples, 0);

		log(String.format(
				"Java path: %d samples, energy=%.6f, RMS=%.6f, peak=%.6f",
				javaSamples.length, javaEnergy,
				Math.sqrt(javaEnergy / javaSamples.length), peakOf(javaSamples)));
		log(String.format(
				"PDSL path: %d samples, energy=%.6f, RMS=%.6f, peak=%.6f",
				pdslSamples.length, pdslEnergy,
				Math.sqrt(pdslEnergy / pdslSamples.length), peakOf(pdslSamples)));

		// Sample-by-sample difference WAV — the audible answer to whether the two
		// paths produce acoustically equivalent output. Scaled by max(|diff|) so
		// even subtle deviations are audible; the scale factor is logged.
		writeDiffWav(javaSamples, pdslSamples, diffWav);

		Assert.assertTrue("Java path WAV must be non-empty", javaWav.length() > 0);
		Assert.assertTrue("PDSL path WAV must be non-empty", pdslWav.length() > 0);
		Assert.assertTrue("Diff path WAV must be non-empty", diffWav.length() > 0);
		Assert.assertTrue("Java path must produce non-silent audio (energy=" + javaEnergy + ")",
				javaEnergy > 1e-9);
		Assert.assertTrue("PDSL path must produce non-silent audio (energy=" + pdslEnergy + ")",
				pdslEnergy > 1e-9);

		double ratio = javaEnergy <= 0 ? 0.0 : pdslEnergy / javaEnergy;
		log(String.format("Energy ratio PDSL/Java = %.4f (1.0 == match)", ratio));

		// The PDSL master stage now applies scale(master_gain) + tanh_act() to
		// match the Java master-bus stage (masterBusGain * bound(., -1, 1)) at
		// MixdownManager.java. The remaining structural mismatches — IIR
		// vs FIR wet filter and per-channel vs shared HP cutoff — produce a
		// sub-octave (≤ ~6×) energy gap that cannot be closed without changing
		// either path. This bound is tight enough to catch a regression that
		// re-removes the master-bus stage (which produced ratios ~24×) while
		// honest about the residual structural drift between the IIR and FIR paths.
		Assert.assertTrue(
				"PDSL/Java energy ratio out of range — expected within 1/6× to 6× "
						+ "of Java energy after master shaping (ratio=" + ratio + ")",
				ratio > 1.0 / 6.0 && ratio < 6.0);
	}

	/**
	 * Smoke test for the {@code mixdown_master_wet} layer: it slices the MAIN rows
	 * {@code [0, channels)} into the dry arm and the WET rows {@code [channels, 2*channels)}
	 * into the efx arm, then runs the per-channel efx feedforward chain and the recursive
	 * feedback grid. This builds the full wet layer (with the efx feedforward neutralised and a
	 * stable no-regeneration feedback) and asserts it compiles, runs, and produces finite,
	 * non-silent output. (Exact equality against the single-input {@code mixdown_master} no
	 * longer holds: the wet layer's efx arm now applies the feedback grid rather than the
	 * feedforward {@code route} of the single-input layer.) The full efx behaviour is judged
	 * by ear via the real-scene A/B.
	 *
	 * @throws IOException if a WAV cannot be written
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void mixdownMasterWetRoutesMainAndWetHalves() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		MixdownManager mixdown = buildFixtureMixdown();
		runFixtureSetup(mixdown);

		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				CHANNELS, PDSL_SIGNAL_SIZE, SAMPLE_RATE, PDSL_FILTER_ORDER,
				WET_LEVEL, PDSL_DELAY_SAMPLES);
		IntToDoubleFunction source =
				t -> Math.sin(2.0 * Math.PI * SOURCE_FREQ_BASE * t / SAMPLE_RATE);

		double[] single = renderPdslMaster(mixdown, "mixdown_master", config, CHANNELS,
				source, TOTAL_FRAMES, false,
				new File(outputDir, "mixdown_master_single.wav"));

		Map<String, Object> neutralEfx = neutralEfxArgs();

		double[] wet = renderPdslMaster(mixdown, "mixdown_master_wet", config, 2 * CHANNELS,
				neutralEfx, source, TOTAL_FRAMES, false,
				new File(outputDir, "mixdown_master_wet_equal.wav"));

		Assert.assertEquals("Both layers must produce the same sample count",
				single.length, wet.length);

		double energy = 0.0;
		for (int i = 0; i < wet.length; i++) {
			Assert.assertTrue("mixdown_master_wet produced a non-finite sample at " + i,
					Double.isFinite(wet[i]));
			energy += wet[i] * wet[i];
		}
		log(String.format("mixdown_master_wet smoke: samples=%d energy=%.6f", wet.length, energy));

		Assert.assertTrue("mixdown_master_wet output must be non-silent (energy=" + energy + ")",
				energy > 1e-9);
	}

	/**
	 * Builds the neutral efx/reverb argument entries for {@code mixdown_master_wet}: wet level
	 * 0 (the per-channel feedforward reduces to dry), transmission 0 (the feedback grid passes
	 * the delayed signal once without recirculating), identity passthrough, and a zero reverb
	 * send, plus validly-shaped state buffers for every ring.
	 *
	 * @return argument entries to merge over the adapter-built map
	 */
	private Map<String, Object> neutralEfxArgs() {
		return neutralEfxArgs(CHANNELS, PDSL_SIGNAL_SIZE);
	}

	/**
	 * Verifies that a collection-valued layer argument is read LIVE on every forward pass
	 * of the compiled model rather than being baked into the graph at build time. The
	 * real-time runner depends on this: all time-varying automation (filter cutoffs,
	 * volume, sends) is delivered through collection slots mutated between forwards.
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void collectionArgIsReadLivePerForward() {
		int sig = 8;
		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource(VERIFICATION_PDSL);

		PackedCollection gain = new PackedCollection(1).fill(2.0);
		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", sig);
		args.put("gain", gain);

		TraversalPolicy inputShape = new TraversalPolicy(1, sig);
		Block block = loader.buildLayer(program, "live_test", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection input = new PackedCollection(inputShape);
		input.fill(1.0);

		double first = compiled.forward(input).toArray(0, sig)[0];
		gain.setMem(0, 3.0);
		double second = compiled.forward(input).toArray(0, sig)[0];
		log(String.format("live-arg probe: first=%.4f (expect 2) second=%.4f (expect 3)",
				first, second));
		Assert.assertEquals("initial gain must apply", 2.0, first, 1e-6);
		Assert.assertEquals(
				"the compiled model must re-read the gain slot on every forward pass; "
						+ "2.0 here means the slot value was baked into the graph at build",
				3.0, second, 1e-6);

		// Same liveness property for fir() coefficients — the delivery mechanism for the
		// per-buffer swept filter responses (coefficient PRODUCERS are input-independent
		// subgraphs and get frozen at compile, so the runner refreshes coefficient VALUES
		// into slots instead; that only works if fir() re-reads its slot per forward).
		int taps = 3;
		PackedCollection coeffs = new PackedCollection(taps);
		coeffs.setMem(1.0, 0.0, 0.0);
		Map<String, Object> firArgs = new HashMap<>();
		firArgs.put("signal_size", sig);
		firArgs.put("fir_taps", taps);
		firArgs.put("coeffs", coeffs);
		Block firBlock = loader.buildLayer(program, "fir_live", inputShape, firArgs);
		Model firModel = new Model(inputShape);
		firModel.add(firBlock);
		CompiledModel firCompiled = firModel.compile();

		double firFirst = firCompiled.forward(input).toArray(0, sig)[sig - 1];
		coeffs.setMem(0.5, 0.0, 0.0);
		double firSecond = firCompiled.forward(input).toArray(0, sig)[sig - 1];
		log(String.format("live-fir probe: first=%.4f (expect 1) second=%.4f (expect 0.5)",
				firFirst, firSecond));
		Assert.assertEquals("initial coefficients must apply", 1.0, firFirst, 1e-6);
		Assert.assertEquals(
				"fir() must re-read its coefficient slot on every forward pass",
				0.5, firSecond, 1e-6);
	}

	/**
	 * Minimal bisection of the per-channel row-0 collapse: a bare {@code for each channel}
	 * over {@code scale(volume[channel])} (no reshape/slice prefix), fed distinct rows. If
	 * this sums correctly while {@link #mixdownMasterWetSumsDistinctChannels} collapses to
	 * row 0, the collapse comes from the arm's {@code reshape -> slice -> reshape} prefix;
	 * if this also collapses, the {@code for each channel} construct itself is at fault.
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void forEachChannelSumsDistinctRows() {
		int channels = 2;
		int sig = 64;
		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource(VERIFICATION_PDSL);

		PackedCollection volume = new PackedCollection(new TraversalPolicy(channels));
		volume.setMem(2.0, 3.0);
		Map<String, Object> args = new HashMap<>();
		args.put("channels", channels);
		args.put("signal_size", sig);
		args.put("volume", volume);

		TraversalPolicy inputShape = new TraversalPolicy(channels, sig);
		PackedCollection input = new PackedCollection(inputShape);
		input.fill(0.0);
		input.range(shape(sig), 0).fill(0.01);
		input.range(shape(sig), sig).fill(0.02);

		// Stage A: rows preserved (no sum) — shows exactly which input row each chain saw.
		Block rowsBlock = loader.buildLayer(program, "fe_rows", inputShape, args);
		Model rowsModel = new Model(inputShape);
		rowsModel.add(rowsBlock);
		double[] rows = rowsModel.compile().forward(input).toArray(0, channels * sig);
		double row0 = rows[0];
		double row1 = rows[sig];
		log(String.format("forEach rows: row0=%.6f (expect 0.020000) row1=%.6f (expect 0.060000; "
				+ "0.030000 means chain 1 received row 0)", row0, row1));

		// Stage B: summed — the production usage.
		Block sumBlock = loader.buildLayer(program, "fe_test", inputShape, args);
		Model sumModel = new Model(inputShape);
		sumModel.add(sumBlock);
		double[] out = sumModel.compile().forward(input).toArray(0, sig);
		double mean = 0.0;
		for (double v : out) mean += v;
		mean /= sig;
		log(String.format("forEach sum: expected=0.080000 measured=%.6f", mean));

		Assert.assertEquals("chain 0 must process its own row times its own gain",
				0.02, row0, 1e-6);
		Assert.assertEquals("chain 1 must process ITS OWN row (0.02 x 3); 0.03 means it "
				+ "received channel 0's row", 0.06, row1, 1e-6);
		Assert.assertEquals("`for each channel` + sum_channels must sum the distinct rows",
				0.08, mean, 1e-6);
	}

	/**
	 * Routing regression with DISTINCT per-channel content: feeds each MAIN row a different DC
	 * value (with the WET rows zero and every gene-driven stage neutralised) and asserts the
	 * master output equals the SUM of the distinct rows. Every earlier routing check fed
	 * identical content to all channels, which cannot detect a dispatch that feeds channel 0's
	 * row to every per-channel chain (the steady output is then {@code channels * row0} —
	 * asserted against explicitly below).
	 *
	 * @throws IOException if the layer cannot be built
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void mixdownMasterWetSumsDistinctChannels() throws IOException {
		MixdownManager mixdown = buildFixtureMixdown();
		runFixtureSetup(mixdown);

		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				CHANNELS, PDSL_SIGNAL_SIZE, SAMPLE_RATE, PDSL_FILTER_ORDER,
				WET_LEVEL, PDSL_DELAY_SAMPLES, 1.0);

		Map<String, Object> args = MixdownManagerPdslAdapter.buildArgsMap(mixdown, config);
		args.putAll(neutralEfxArgs());

		// Neutralise the gene-driven main-arm stages so the expected output is exact
		// arithmetic: unity volume, identity (delta) FIR coefficients for the per-channel
		// high-pass and the master low-pass, unity master gain.
		args.put("volume", onesCollection(CHANNELS));
		int taps = PDSL_FILTER_ORDER + 1;
		args.put("hp_coeffs", identityFirBank(CHANNELS, taps));
		args.put("lp_coeffs", identityFir(taps));
		args.put("master_gain", 1.0);

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");
		TraversalPolicy inputShape = new TraversalPolicy(2 * CHANNELS, PDSL_SIGNAL_SIZE);
		Block block = loader.buildLayer(program, "mixdown_master_wet", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		// MAIN rows carry DISTINCT DC values; WET rows are silent.
		double[] channelValues = new double[CHANNELS];
		double expected = 0.0;
		for (int ch = 0; ch < CHANNELS; ch++) {
			// Row 0 deliberately SILENT: a dispatch that feeds every chain row 0 then
			// produces total silence, which is unmistakable in the failure message.
			channelValues[ch] = 0.01 * ch;
			expected += channelValues[ch];
		}
		double conflated = CHANNELS * channelValues[0];

		PackedCollection input = new PackedCollection(inputShape);
		double[] inData = new double[2 * CHANNELS * PDSL_SIGNAL_SIZE];
		for (int ch = 0; ch < CHANNELS; ch++) {
			Arrays.fill(inData, ch * PDSL_SIGNAL_SIZE, (ch + 1) * PDSL_SIGNAL_SIZE,
					channelValues[ch]);
		}
		input.setMem(inData);

		double[] out = compiled.forward(input).toArray(0, PDSL_SIGNAL_SIZE);
		// Skip the FIR settling region at the start of the buffer.
		int settle = 4 * (PDSL_FILTER_ORDER + 1);
		double mean = 0.0;
		for (int i = settle; i < PDSL_SIGNAL_SIZE; i++) {
			mean += out[i];
		}
		mean /= (PDSL_SIGNAL_SIZE - settle);

		log(String.format("distinct-channel sum: expected=%.6f conflated=%.6f measured=%.6f",
				expected, conflated, mean));
		Assert.assertTrue(
				"master output (" + mean + ") must NOT equal channels x row0 ("
						+ conflated + ") — that indicates every per-channel chain "
						+ "received channel 0's row",
				Math.abs(mean - conflated) > Math.abs(expected - conflated) / 2.0);
		Assert.assertEquals(
				"master output must equal the sum of the distinct per-channel rows",
				expected, mean, 0.1 * expected);
	}

	/**
	 * Writes a WAV file containing the sample-by-sample difference between the Java and
	 * PDSL paths, normalised so the loudest absolute value sits at ±0.95 (so the diff
	 * is audible even when the absolute deviation is small). The applied scale factor
	 * is logged.
	 *
	 * @param javaSamples mono Java-path output
	 * @param pdslSamples mono PDSL-path output
	 * @param outputFile  destination WAV
	 */
	private void writeDiffWav(double[] javaSamples, double[] pdslSamples, File outputFile)
			throws IOException {
		int n = Math.min(javaSamples.length, pdslSamples.length);
		double[] diff = new double[n];
		double peak = 0.0;
		for (int i = 0; i < n; i++) {
			diff[i] = pdslSamples[i] - javaSamples[i];
			double a = Math.abs(diff[i]);
			if (a > peak) peak = a;
		}
		double scale = peak > 0.0 ? 0.95 / peak : 1.0;
		float[] floatSamples = new float[n];
		for (int i = 0; i < n; i++) {
			floatSamples[i] = (float) Math.max(-1.0, Math.min(1.0, diff[i] * scale));
		}
		log(String.format("Diff WAV peak=%.6f, scale-to-fullscale=%.6f", peak, scale));
		PdslAudioDemoTest.writeDemoWav(outputFile, floatSamples, SAMPLE_RATE);
	}

	/**
	 * Renders {@link MixdownManager#cells} for {@link #DURATION_SECONDS} seconds,
	 * writing the master-bus output to {@code outputFile} and returning the
	 * loaded mono samples.
	 *
	 * @param mixdown    the constructed mixdown manager
	 * @param automation its automation manager
	 * @param time       the global clock manager
	 * @param outputFile destination WAV file
	 * @return mono audio samples ({@link #TOTAL_FRAMES} entries)
	 */
	private double[] renderJavaPath(MixdownManager mixdown, AutomationManager automation,
									GlobalTimeManager time, File outputFile) throws IOException {
		WaveOutput mixOut = new WaveOutput(outputFile);
		MultiChannelAudioOutput output = new MultiChannelAudioOutput(mixOut);

		File sourceWav = getTestWavFile(SOURCE_FREQ_BASE, DURATION_SECONDS);
		CellList sources = w(0, c(0.0), c(1.0), WaveData.load(sourceWav));

		OperationList setup = new OperationList("verification java setup");
		setup.add(automation.setup());
		setup.add(mixdown.setup());
		setup.add(time.setup());
		setup.get().run();

		CellList graph = mixdown.cells(sources, output, ChannelInfo.StereoChannel.LEFT);
		graph.addRequirement(time::tick);

		int batchFrames = SAMPLE_RATE / 4;
		int batches = TOTAL_FRAMES / batchFrames;
		graph.setup().get().run();
		Runnable batchTick = loop(graph.tick(), batchFrames).get();
		for (int b = 0; b < batches; b++) batchTick.run();
		mixOut.write().get().run();

		WaveData wav = WaveData.load(outputFile);
		try {
			PackedCollection data = wav.getData();
			int n = Math.min(data.getMemLength(), TOTAL_FRAMES);
			double[] samples = new double[n];
			for (int i = 0; i < n; i++) samples[i] = data.toDouble(i);
			return samples;
		} finally {
			wav.destroy();
		}
	}

	/**
	 * Renders the PDSL {@code mixdown_master} layer for {@link #DURATION_SECONDS}
	 * seconds using {@link MixdownManagerPdslAdapter#buildArgsMap}, writes the
	 * mono output to {@code outputFile}, and returns the rendered samples.
	 *
	 * @param mixdown    constructed mixdown manager whose chromosomes drive the args
	 * @param outputFile destination WAV file
	 * @return mono audio samples (up to {@link #TOTAL_FRAMES} entries)
	 */
	private double[] renderPdslPath(MixdownManager mixdown, File outputFile) throws IOException {
		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				CHANNELS, PDSL_SIGNAL_SIZE, SAMPLE_RATE, PDSL_FILTER_ORDER,
				WET_LEVEL, PDSL_DELAY_SAMPLES);
		return renderPdslMaster(mixdown, "mixdown_master", config, CHANNELS,
				t -> Math.sin(2.0 * Math.PI * SOURCE_FREQ_BASE * t / SAMPLE_RATE),
				TOTAL_FRAMES, false, outputFile);
	}

	/**
	 * Compiles the PDSL {@code mixdown_master} layer from {@code mixdown}'s genome and renders
	 * {@link #TOTAL_FRAMES} frames through it, drawing each input sample (shared across channels)
	 * from {@code sampleAt} indexed by absolute frame. Writes the mono output to {@code outputFile}
	 * and returns the samples.
	 *
	 * @param mixdown    constructed mixdown manager whose chromosomes drive the args
	 * @param sampleAt   supplies the input sample value at an absolute frame index
	 * @param outputFile destination WAV file
	 * @return mono audio samples
	 */
	private double[] renderPdslMaster(MixdownManager mixdown, String layerName,
			MixdownManagerPdslAdapter.Config config, int inputChannels, IntToDoubleFunction sampleAt,
			int totalFrames, boolean advanceClock, File outputFile) throws IOException {
		return renderPdslMaster(mixdown, layerName, config, inputChannels,
				Collections.emptyMap(), sampleAt, totalFrames, advanceClock, outputFile);
	}

	/**
	 * Compiles {@code layerName} from {@code mixdown}'s genome (with {@code extraArgs} merged
	 * over the adapter-built argument map) and renders {@code totalFrames} frames through it,
	 * drawing each input sample (shared across all {@code inputChannels} rows) from
	 * {@code sampleAt}. Writes the mono output to {@code outputFile} and returns the samples.
	 *
	 * @param mixdown       constructed mixdown manager whose chromosomes drive the args
	 * @param layerName     the PDSL layer to build
	 * @param config        structural configuration
	 * @param inputChannels number of input rows to fill (e.g. {@code 2*channels} for the wet layer)
	 * @param extraArgs     extra argument-map entries merged over the adapter args (may be empty)
	 * @param sampleAt      supplies the input sample value at an absolute frame index
	 * @param totalFrames   number of frames to render
	 * @param advanceClock  whether to advance the shared clock one buffer per pass
	 * @param outputFile    destination WAV file
	 * @return mono audio samples
	 */
	private double[] renderPdslMaster(MixdownManager mixdown, String layerName,
			MixdownManagerPdslAdapter.Config config, int inputChannels,
			Map<String, Object> extraArgs, IntToDoubleFunction sampleAt,
			int totalFrames, boolean advanceClock, File outputFile) throws IOException {
		int sig = config.signalSize;
		Map<String, Object> args = MixdownManagerPdslAdapter.buildArgsMap(mixdown, config);
		args.putAll(extraArgs);

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");

		TraversalPolicy inputShape = new TraversalPolicy(inputChannels, sig);
		Block block = loader.buildLayer(program, layerName, inputShape, args);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		// Confirm the Block→CellList helper produces the expected shape.
		// We don't actually drive the CellList here (the comparison runs the
		// CompiledModel directly), but the helper is exercised so any breakage
		// in its narrow contract surfaces as part of this test.
		CellList wrapped = MixdownManagerPdslAdapter.wrapBlockAsCellList(block);
		Assert.assertEquals("wrapBlockAsCellList must produce a single-cell list",
				1, wrapped.size());

		int passes = totalFrames / sig;
		double[] samples = new double[passes * sig];
		float[] floatSamples = new float[samples.length];

		PackedCollection input = new PackedCollection(inputShape);
		double[] inData = new double[inputChannels * sig];

		// When advanceClock is set, step the shared clock forward one buffer's worth of frames
		// after each forward pass, so the genome/automation producers (HP/LP cutoffs, volume)
		// evaluate at the buffer's playback time and the automation sweeps over the render.
		Runnable clockAdvance = advanceClock
				? loop(fixtureTime.tick(), sig).get() : null;

		for (int pass = 0; pass < passes; pass++) {
			int sampleOffset = pass * sig;
			for (int t = 0; t < sig; t++) {
				double v = sampleAt.applyAsDouble(sampleOffset + t);
				for (int c = 0; c < inputChannels; c++) {
					inData[c * sig + t] = v;
				}
			}
			input.setMem(inData);
			double[] passOut = compiled.forward(input).toArray(0, sig);
			System.arraycopy(passOut, 0, samples, sampleOffset, sig);
			for (int i = 0; i < sig; i++) {
				floatSamples[sampleOffset + i] = (float) Math.max(-1.0,
						Math.min(1.0, passOut[i]));
			}
			if (clockAdvance != null) clockAdvance.run();
		}

		PdslAudioDemoTest.writeDemoWav(outputFile, floatSamples, SAMPLE_RATE);
		return samples;
	}

	/**
	 * Sanity-listen demo: retriggers a source clip every second for {@link #DEMO_SECONDS} seconds,
	 * runs it through the PDSL {@code mixdown_master} process with the clock advancing per buffer
	 * (so the gene/automation-driven HP/LP cutoffs and volume sweep over time), and writes
	 * {@code results/pdsl-audio-dsp/pdsl_mixdown_looped_sample.wav} for manual listening. The long
	 * duration lets time-evolving behaviour — automation sweeps and the efx delay/feedback tail —
	 * be heard, which a two-second clip cannot show. Confirms the PDSL DSP path produces
	 * non-silent, expected-sounding output before further migration work; asserts only non-silence.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void pdslMixdownLoopedSampleDemo() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		MixdownManager mixdown = buildFixtureMixdown();
		runFixtureSetup(mixdown);

		double[] source = loadLoopSource();
		int loopFrames = SAMPLE_RATE; // retrigger the clip every 1 second
		IntToDoubleFunction looped = t -> {
			int pos = t % loopFrames;
			return pos < source.length ? source[pos] : 0.0;
		};

		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				CHANNELS, DEMO_SIGNAL_SIZE, SAMPLE_RATE, PDSL_FILTER_ORDER,
				DEMO_WET_LEVEL, DEMO_DELAY_SAMPLES);
		File demoWav = new File(outputDir, "pdsl_mixdown_looped_sample.wav");
		double[] out = renderPdslMaster(mixdown, "mixdown_master", config, CHANNELS, looped,
				(int) (DEMO_SECONDS * SAMPLE_RATE), true, demoWav);
		double e = energy(out, 0);

		log(String.format("PDSL looped-sample demo: %d samples, energy=%.6f, peak=%.6f, wav=%s",
				out.length, e, peakOf(out), demoWav.getAbsolutePath()));

		Assert.assertTrue("demo WAV must be non-empty", demoWav.length() > 0);
		Assert.assertTrue("demo output must be non-silent (energy=" + e + ")", e > 1e-9);
	}

	/**
	 * Sanity-listen demo for the closed-loop {@code feedback_comb} layer (the PDSL rendition of
	 * EfxManager's recursive {@code .mself} echo). Retriggers a real source clip every second for
	 * {@link #DEMO_SECONDS} seconds through {@code feedback(...)} with a multi-frame ring
	 * ({@link #COMB_DELAY_SAMPLES} &gt; one frame), writing
	 * {@code results/pdsl-audio-dsp/pdsl_feedback_comb_looped_sample.wav} so the regenerating echo
	 * train can be heard. This is the acoustic gate before the EfxManager feedback migration. The
	 * numeric decay of the feedback path is proven separately by {@code DelayNetworkBehaviorTest};
	 * here we assert only that the closed loop stays finite (does not blow up) and produces
	 * non-silent output through the real PDSL surface.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void pdslFeedbackCombLoopedSampleDemo() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		IntToDoubleFunction looped = loopedSource();
		double[] mono = renderFeedbackCombMono(1, DEMO_SIGNAL_SIZE, COMB_BUFFER_FRAMES,
				new double[]{COMB_DELAY_SAMPLES}, new double[]{COMB_FEEDBACK_GAIN},
				new double[]{COMB_WET_LEVEL}, looped, (int) (DEMO_SECONDS * SAMPLE_RATE));

		File demoWav = new File(outputDir, "pdsl_feedback_comb_looped_sample.wav");
		writeMonoWav(demoWav, mono);

		int firstBad = firstNonFinite(mono);
		double e = energy(mono, 0);
		log(String.format("feedback comb demo: samples=%d firstNonFinite=%d energy=%.6f peak=%.6f wav=%s",
				mono.length, firstBad, e, peakOf(mono), demoWav.getAbsolutePath()));

		Assert.assertEquals("feedback comb produced a non-finite sample at index " + firstBad,
				-1, firstBad);
		Assert.assertTrue("demo WAV must be non-empty", demoWav.length() > 0);
		Assert.assertTrue("feedback comb output must be non-silent (energy=" + e + ")", e > 1e-9);
	}

	/**
	 * Sanity-listen demo for the M×M cross-channel feedback grid — the PDSL rendition of
	 * MixdownManager.createEfx's {@code .mself(fi(), transmission, fc(wetOut))} reverb/transmission
	 * grid. Runs {@link #GRID_CHANNELS} taps with prime-ish multi-frame delays and a scaled-
	 * Householder transmission matrix (orthogonal → spectral radius {@link #GRID_FEEDBACK_GAIN},
	 * so a guaranteed-stable decaying tail with full cross-channel mixing), summed to mono and
	 * written to {@code results/pdsl-audio-dsp/pdsl_feedback_grid_looped_sample.wav}.
	 *
	 * <p>Beyond finite/non-silent/bounded, this asserts the cross-channel coupling is actually
	 * doing something: the coupled grid's output must differ from a diagonal-only matrix of the
	 * same per-tap self-gain (four independent combs). If the off-diagonal feedback were ignored
	 * the two would be identical, so a non-zero difference proves the M×M transmission grid routes
	 * energy across channels.</p>
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void pdslFeedbackGridLoopedSampleDemo() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		int channels = GRID_CHANNELS;
		IntToDoubleFunction looped = loopedSource();
		int totalFrames = (int) (DEMO_SECONDS * SAMPLE_RATE);

		// Prime-ish per-tap delays, all longer than one frame and shorter than the ring, for
		// natural (non-metallic) diffusion.
		double[] delays = {11000, 13003, 16007, 19001};
		double[] grid = householderGrid(channels, GRID_FEEDBACK_GAIN);
		double[] passthrough = scaledIdentity(channels, GRID_WET_LEVEL);

		// Reference: same per-tap self-gain (the Householder diagonal) but NO cross-channel
		// coupling — four independent combs. Used to prove the off-diagonals matter.
		double selfGain = grid[0];  // diagonal entry of the scaled Householder
		double[] diagonalOnly = scaledIdentity(channels, selfGain);

		double[] mono = renderFeedbackCombMono(channels, DEMO_SIGNAL_SIZE, COMB_BUFFER_FRAMES,
				delays, grid, passthrough, looped, totalFrames);
		double[] diag = renderFeedbackCombMono(channels, DEMO_SIGNAL_SIZE, COMB_BUFFER_FRAMES,
				delays, diagonalOnly, passthrough, looped, totalFrames);

		File demoWav = new File(outputDir, "pdsl_feedback_grid_looped_sample.wav");
		writeMonoWav(demoWav, mono);

		int firstBad = firstNonFinite(mono);
		double e = energy(mono, 0);
		double couplingDiff = 0.0;
		for (int i = 0; i < mono.length; i++) {
			double d = mono[i] - diag[i];
			couplingDiff += d * d;
		}
		log(String.format("feedback grid demo (%dch): samples=%d firstNonFinite=%d energy=%.6f "
						+ "peak=%.6f couplingDiff=%.6f wav=%s",
				channels, mono.length, firstBad, e, peakOf(mono), couplingDiff,
				demoWav.getAbsolutePath()));

		Assert.assertEquals("feedback grid produced a non-finite sample at index " + firstBad,
				-1, firstBad);
		Assert.assertTrue("demo WAV must be non-empty", demoWav.length() > 0);
		Assert.assertTrue("grid output must be non-silent (energy=" + e + ")", e > 1e-9);
		Assert.assertTrue("grid peak must stay bounded — feedback must decay, not blow up (peak="
				+ peakOf(mono) + ")", peakOf(mono) < 50.0);
		Assert.assertTrue("cross-channel coupling must change the output vs independent combs "
				+ "(couplingDiff=" + couplingDiff + ")", couplingDiff > 1e-6);
	}

	/**
	 * Builds the every-1-second retrigger function over the curated loop source. Shared by the
	 * feedback-comb and feedback-grid demos.
	 *
	 * @return a function mapping absolute frame index to the looped source sample
	 * @throws IOException if the source clip cannot be loaded
	 */
	private IntToDoubleFunction loopedSource() throws IOException {
		double[] source = loadLoopSource();
		int loopFrames = SAMPLE_RATE; // retrigger the clip every 1 second
		return t -> {
			int pos = t % loopFrames;
			return pos < source.length ? source[pos] : 0.0;
		};
	}

	/**
	 * Compiles the {@code feedback_comb} PDSL layer for the given channel count and matrices, then
	 * runs {@code totalFrames} of {@code sampleAt} (broadcast to every channel) through it with a
	 * multi-frame ring, summing all channels to a mono signal. Shared by the single-channel comb
	 * and the M×M grid demos.
	 *
	 * @param channels             number of feedback taps/channels
	 * @param sig                  samples per frame
	 * @param bufFrames            ring depth in frames ({@code bufSize = bufFrames * sig})
	 * @param delaySamplesData     per-channel delay in samples, length {@code channels}
	 * @param transmissionRowMajor row-major transmission matrix, length {@code channels*channels}
	 * @param passthroughRowMajor  row-major passthrough matrix, length {@code channels*channels}
	 * @param sampleAt             input sample at an absolute frame index (shared across channels)
	 * @param totalFrames          number of frames to render
	 * @return the mono (channel-summed) output samples
	 */
	private double[] renderFeedbackCombMono(int channels, int sig, int bufFrames,
			double[] delaySamplesData, double[] transmissionRowMajor,
			double[] passthroughRowMajor, IntToDoubleFunction sampleAt, int totalFrames) {
		int bufSize = bufFrames * sig;
		PackedCollection delaySamples = new PackedCollection(channels);
		delaySamples.setMem(delaySamplesData);
		PackedCollection transmission = new PackedCollection(new TraversalPolicy(channels, channels));
		transmission.setMem(transmissionRowMajor);
		PackedCollection passthrough = new PackedCollection(new TraversalPolicy(channels, channels));
		passthrough.setMem(passthroughRowMajor);
		PackedCollection buffers = new PackedCollection(channels * bufSize);
		buffers.fill(0.0);
		PackedCollection heads = new PackedCollection(channels);
		heads.fill(0.0);

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		TraversalPolicy inputShape = new TraversalPolicy(channels, sig);
		Map<String, Object> args = new HashMap<>();
		args.put("channels", channels);
		args.put("signal_size", sig);
		args.put("delay_samples", delaySamples);
		args.put("transmission", transmission);
		args.put("passthrough", passthrough);
		args.put("buffers", buffers);
		args.put("heads", heads);

		Block block = loader.buildLayer(program, "feedback_comb", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		int passes = totalFrames / sig;
		double[] mono = new double[passes * sig];
		PackedCollection input = new PackedCollection(inputShape);
		double[] inData = new double[channels * sig];

		for (int pass = 0; pass < passes; pass++) {
			int off = pass * sig;
			for (int t = 0; t < sig; t++) {
				double v = sampleAt.applyAsDouble(off + t);
				for (int c = 0; c < channels; c++) {
					inData[c * sig + t] = v;
				}
			}
			input.setMem(inData);
			double[] passOut = compiled.forward(input).toArray(0, channels * sig);
			for (int t = 0; t < sig; t++) {
				double s = 0.0;
				for (int c = 0; c < channels; c++) {
					s += passOut[c * sig + t];
				}
				mono[off + t] = s;
			}
		}
		return mono;
	}

	/** Clamps a mono signal to [-1, 1] floats and writes it as a WAV. */
	private void writeMonoWav(File f, double[] mono) throws IOException {
		float[] floats = new float[mono.length];
		for (int i = 0; i < mono.length; i++) {
			floats[i] = (float) Math.max(-1.0, Math.min(1.0, mono[i]));
		}
		PdslAudioDemoTest.writeDemoWav(f, floats, SAMPLE_RATE);
	}

	/**
	 * Returns the index of the first non-finite sample, or {@code -1} if all samples are finite.
	 *
	 * @param x the signal to scan
	 * @return the first non-finite index, or -1
	 */
	private static int firstNonFinite(double[] x) {
		for (int i = 0; i < x.length; i++) {
			if (!Double.isFinite(x[i])) return i;
		}
		return -1;
	}

	/**
	 * Builds a row-major scaled Householder reflection {@code gain * (I - 2 v vᵀ)} with
	 * {@code v} the unit vector of all {@code 1/√n}. The reflection is orthogonal (eigenvalues
	 * ±1), so the scaled matrix has spectral radius {@code gain} — a guaranteed-stable feedback
	 * matrix (for {@code gain < 1}) that nonetheless mixes every channel into every other.
	 *
	 * @param n    matrix dimension (channel count)
	 * @param gain scale factor; the resulting spectral radius
	 * @return the row-major {@code n*n} transmission matrix
	 */
	private static double[] householderGrid(int n, double gain) {
		double[] m = new double[n * n];
		double off = 2.0 / n;  // 2 * (1/√n)^2
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				double h = (i == j ? 1.0 : 0.0) - off;
				m[i * n + j] = gain * h;
			}
		}
		return m;
	}

	/**
	 * Builds a row-major {@code n*n} diagonal matrix with {@code scale} on the diagonal.
	 *
	 * @param n     matrix dimension
	 * @param scale the diagonal value
	 * @return the row-major diagonal matrix
	 */
	private static double[] scaledIdentity(int n, double scale) {
		double[] m = new double[n * n];
		for (int i = 0; i < n; i++) {
			m[i * n + i] = scale;
		}
		return m;
	}

	/**
	 * Regression guard: runs a retriggered real sample through the main bus only
	 * ({@code mixdown_main_bus} = per-channel HP + volume + sum) and asserts every output sample
	 * is finite. This guards the requirement that the fixture's {@code setup()} be run before
	 * rendering the PDSL path — without it the adapter's gene/automation producers read
	 * uninitialised state and the whole render collapses to NaN. Logs the first non-finite sample
	 * index (-1 when clean) and the energy.
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void pdslMainBusRealAudioProbe() throws IOException {
		new File("results/pdsl-audio-dsp").mkdirs();
		MixdownManager mixdown = buildFixtureMixdown();
		runFixtureSetup(mixdown);
		double[] source = loadLoopSource();
		int loopFrames = SAMPLE_RATE;
		IntToDoubleFunction looped = t -> {
			int pos = t % loopFrames;
			return pos < source.length ? source[pos] : 0.0;
		};
		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				CHANNELS, PDSL_SIGNAL_SIZE, SAMPLE_RATE, PDSL_FILTER_ORDER,
				WET_LEVEL, PDSL_DELAY_SAMPLES);
		File probeWav = new File("results/pdsl-audio-dsp/pdsl_main_bus_probe.wav");
		double[] out = renderPdslMaster(mixdown, "mixdown_main_bus", config, CHANNELS, looped,
				TOTAL_FRAMES, false, probeWav);

		int firstBad = -1;
		for (int i = 0; i < out.length; i++) {
			if (!Double.isFinite(out[i])) { firstBad = i; break; }
		}
		log(String.format("main_bus probe: samples=%d firstNonFinite=%d energy=%.6f peak=%.6f",
				out.length, firstBad, energy(out, 0), peakOf(out)));
		Assert.assertEquals("main_bus (HP+volume+sum) produced a non-finite sample at index "
				+ firstBad, -1, firstBad);
	}

	/**
	 * Loads a clip (capped at one second) to retrigger through the demo. Prefers the first
	 * loadable {@code .wav} in the curated library when present; otherwise falls back to a short
	 * synthesised tone so the demo runs anywhere.
	 *
	 * @return mono sample data, at most {@link #SAMPLE_RATE} frames
	 */
	private double[] loadLoopSource() throws IOException {
		File library = new File("/Users/Shared/Music/Samples");
		if (library.isDirectory()) {
			File[] wavs = library.listFiles((d, n) -> n.toLowerCase().endsWith(".wav"));
			if (wavs != null) {
				Arrays.sort(wavs);
				for (int i = 0; i < wavs.length && i < 25; i++) {
					double[] clip = tryLoadClip(wavs[i]);
					if (clip != null) return clip;
				}
			}
		}
		double[] synthetic = tryLoadClip(getTestWavFile(SOURCE_FREQ_BASE, 0.5));
		if (synthetic == null) {
			throw new IOException("no loadable source clip for the PDSL demo");
		}
		return synthetic;
	}

	/**
	 * Attempts to load a {@code .wav} file's first channel, capped at one second of frames.
	 *
	 * @param f the WAV file to load
	 * @return mono sample data, or {@code null} if the file cannot be loaded
	 */
	private double[] tryLoadClip(File f) {
		try {
			WaveData wav = WaveData.load(f);
			try {
				PackedCollection data = wav.getData();
				int n = Math.min(data.getMemLength(), SAMPLE_RATE);
				if (n <= 0) return null;
				double[] clip = new double[n];
				double peak = 0.0;
				for (int i = 0; i < n; i++) {
					double v = data.toDouble(i);
					// Drop non-finite samples (some library files carry odd formats); a
					// single NaN propagates through the DSP and corrupts the whole render.
					if (!Double.isFinite(v)) v = 0.0;
					clip[i] = v;
					peak = Math.max(peak, Math.abs(v));
				}
				if (peak < 1.0e-6) return null;  // silent / garbage — try the next file
				// Normalise to a safe peak so the DSP always sees a sane input range
				// regardless of the source file's native scale.
				double norm = 0.9 / peak;
				for (int i = 0; i < n; i++) clip[i] *= norm;
				log("loop source: " + f.getName() + " (" + n + " frames, peak " + peak + ")");
				return clip;
			} finally {
				wav.destroy();
			}
		} catch (Exception e) {
			return null;
		}
	}

}
