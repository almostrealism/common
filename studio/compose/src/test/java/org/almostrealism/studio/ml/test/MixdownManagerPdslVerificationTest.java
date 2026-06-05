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
 * <p>Acoustic differences between the two paths are documented in
 * {@code docs/plans/PDSL_AUDIO_DSP.md} Section 8 and Section 12. The most
 * load-bearing structural mismatches are:
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
		implements CellFeatures, AudioTestFeatures, FirFilterTestFeatures, LayerFeatures {

	/** Number of audio channels used for both paths. */
	private static final int CHANNELS = 2;

	/** Number of delay layers in the EFX bus. Matches CHANNELS for square routing. */
	private static final int DELAY_LAYERS = CHANNELS;

	/** Audio sample rate. Matches the production line. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Render duration in seconds. Short enough to keep CI runtime modest. */
	private static final double DURATION_SECONDS = 2.0;

	/** Total samples per channel that both paths render. */
	private static final int TOTAL_FRAMES = (int) (DURATION_SECONDS * SAMPLE_RATE);

	/** Samples per PDSL forward pass — must divide TOTAL_FRAMES evenly. */
	private static final int PDSL_SIGNAL_SIZE = SAMPLE_RATE / 16;

	/** FIR filter order for the PDSL wet bus. */
	private static final int PDSL_FILTER_ORDER = 40;

	/** Wet-bus level constant. */
	private static final double WET_LEVEL = 0.35;

	/** Static delay length used by the PDSL path's delay primitive. */
	private static final int PDSL_DELAY_SAMPLES = 256;

	/** Genome parameter count. Oversized so any chromosome layout fits. */
	private static final int GENOME_PARAMS = 256;

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
		// MixdownManager.java:770-782. The remaining structural mismatches — IIR
		// vs FIR wet filter and per-channel vs shared HP cutoff — produce a
		// sub-octave (≤ ~6×) energy gap that cannot be closed without changing
		// either path. This bound is tight enough to catch a regression that
		// re-removes the master-bus stage (which produced ratios ~24×) while
		// honest about the residual structural drift; see PDSL_AUDIO_DSP.md
		// Section 8 ("What Is Complete" → real-audio verification).
		Assert.assertTrue(
				"PDSL/Java energy ratio out of range — expected within 1/6× to 6× "
						+ "of Java energy after master shaping (ratio=" + ratio + ")",
				ratio > 1.0 / 6.0 && ratio < 6.0);
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
		return renderPdslMaster(mixdown, "mixdown_master", config,
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
			MixdownManagerPdslAdapter.Config config, IntToDoubleFunction sampleAt,
			int totalFrames, boolean advanceClock, File outputFile) throws IOException {
		int sig = config.signalSize;
		Map<String, Object> args = MixdownManagerPdslAdapter.buildArgsMap(mixdown, config);

		PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");

		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, sig);
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
		double[] inData = new double[CHANNELS * sig];

		// When advanceClock is set, step the shared clock forward one buffer's worth of frames
		// after each forward pass, so the genome/automation producers (HP/LP cutoffs, volume)
		// evaluate at the buffer's playback time and the automation sweeps over the render.
		Runnable clockAdvance = advanceClock
				? loop(fixtureTime.tick(), sig).get() : null;

		for (int pass = 0; pass < passes; pass++) {
			int sampleOffset = pass * sig;
			for (int t = 0; t < sig; t++) {
				double v = sampleAt.applyAsDouble(sampleOffset + t);
				for (int c = 0; c < CHANNELS; c++) {
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
		double[] out = renderPdslMaster(mixdown, "mixdown_master", config, looped,
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

		int channels = 1;
		int sig = DEMO_SIGNAL_SIZE;
		int bufSize = COMB_BUFFER_FRAMES * sig;

		double[] source = loadLoopSource();
		int loopFrames = SAMPLE_RATE; // retrigger the clip every 1 second
		IntToDoubleFunction looped = t -> {
			int pos = t % loopFrames;
			return pos < source.length ? source[pos] : 0.0;
		};

		PackedCollection delaySamples = new PackedCollection(channels);
		delaySamples.setMem(new double[]{COMB_DELAY_SAMPLES});
		PackedCollection transmission = new PackedCollection(new TraversalPolicy(channels, channels));
		transmission.setMem(new double[]{COMB_FEEDBACK_GAIN});
		PackedCollection passthrough = new PackedCollection(new TraversalPolicy(channels, channels));
		passthrough.setMem(new double[]{COMB_WET_LEVEL});
		PackedCollection buffers = new PackedCollection(channels * bufSize);
		buffers.setMem(new double[channels * bufSize]);
		PackedCollection heads = new PackedCollection(channels);
		heads.setMem(new double[channels]);

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

		int totalFrames = (int) (DEMO_SECONDS * SAMPLE_RATE);
		int passes = totalFrames / sig;
		double[] out = new double[passes * sig];
		float[] floatSamples = new float[out.length];
		PackedCollection input = new PackedCollection(inputShape);
		double[] inData = new double[sig];

		for (int pass = 0; pass < passes; pass++) {
			int off = pass * sig;
			for (int t = 0; t < sig; t++) {
				inData[t] = looped.applyAsDouble(off + t);
			}
			input.setMem(inData);
			double[] passOut = compiled.forward(input).toArray(0, sig);
			System.arraycopy(passOut, 0, out, off, sig);
			for (int i = 0; i < sig; i++) {
				floatSamples[off + i] = (float) Math.max(-1.0, Math.min(1.0, passOut[i]));
			}
		}

		File demoWav = new File(outputDir, "pdsl_feedback_comb_looped_sample.wav");
		PdslAudioDemoTest.writeDemoWav(demoWav, floatSamples, SAMPLE_RATE);

		int firstBad = -1;
		for (int i = 0; i < out.length; i++) {
			if (!Double.isFinite(out[i])) { firstBad = i; break; }
		}
		double e = energy(out, 0);
		log(String.format("feedback comb demo: samples=%d firstNonFinite=%d energy=%.6f peak=%.6f wav=%s",
				out.length, firstBad, e, peakOf(out), demoWav.getAbsolutePath()));

		Assert.assertEquals("feedback comb produced a non-finite sample at index " + firstBad,
				-1, firstBad);
		Assert.assertTrue("demo WAV must be non-empty", demoWav.length() > 0);
		Assert.assertTrue("feedback comb output must be non-silent (energy=" + e + ")", e > 1e-9);
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
		double[] out = renderPdslMaster(mixdown, "mixdown_main_bus", config, looped,
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
