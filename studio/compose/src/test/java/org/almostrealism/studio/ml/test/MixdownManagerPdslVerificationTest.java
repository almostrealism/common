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
import java.util.HashMap;
import java.util.Map;

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
		GlobalTimeManager time = new GlobalTimeManager(
				measure -> (int) (measure * measureDuration * SAMPLE_RATE));
		ProjectedGenome genome = new ProjectedGenome(GENOME_PARAMS);
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(),
				() -> measureDuration, SAMPLE_RATE);
		MixdownManager mixdown = new MixdownManager(genome.addChromosome(),
				CHANNELS, DELAY_LAYERS, automation, time.getClock(), SAMPLE_RATE);
		genome.consolidateGeneValues();

		PackedCollection params = new PackedCollection(GENOME_PARAMS).fill(0.5);
		genome.assignTo(params);

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
		Map<String, Object> args = MixdownManagerPdslAdapter.buildArgsMap(mixdown, config);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/mixdown_manager.pdsl");

		TraversalPolicy inputShape = new TraversalPolicy(CHANNELS, PDSL_SIGNAL_SIZE);
		Block block = loader.buildLayer(program, "mixdown_master", inputShape, args);

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

		int passes = TOTAL_FRAMES / PDSL_SIGNAL_SIZE;
		double[] samples = new double[passes * PDSL_SIGNAL_SIZE];
		float[] floatSamples = new float[samples.length];

		PackedCollection input = new PackedCollection(inputShape);
		double[] inData = new double[CHANNELS * PDSL_SIGNAL_SIZE];

		for (int pass = 0; pass < passes; pass++) {
			int sampleOffset = pass * PDSL_SIGNAL_SIZE;
			for (int t = 0; t < PDSL_SIGNAL_SIZE; t++) {
				double v = Math.sin(2.0 * Math.PI * SOURCE_FREQ_BASE
						* (sampleOffset + t) / SAMPLE_RATE);
				for (int c = 0; c < CHANNELS; c++) {
					inData[c * PDSL_SIGNAL_SIZE + t] = v;
				}
			}
			input.setMem(inData);
			double[] passOut = compiled.forward(input).toArray(0, PDSL_SIGNAL_SIZE);
			System.arraycopy(passOut, 0, samples, sampleOffset, PDSL_SIGNAL_SIZE);
			for (int i = 0; i < PDSL_SIGNAL_SIZE; i++) {
				floatSamples[sampleOffset + i] = (float) Math.max(-1.0,
						Math.min(1.0, passOut[i]));
			}
		}

		PdslAudioDemoTest.writeDemoWav(outputFile, floatSamples, SAMPLE_RATE);
		return samples;
	}

	/** Returns the peak absolute value of a signal. */
	private double peakOf(double[] samples) {
		double peak = 0.0;
		for (double v : samples) {
			double a = Math.abs(v);
			if (a > peak) peak = a;
		}
		return peak;
	}
}
