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

package org.almostrealism.studio.arrange.test;

import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.AudioTestFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.studio.arrange.AutomationManager;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.lang.reflect.Field;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Isolated tests of {@link MixdownManager} that drive the manager with a
 * synthetic, low-frequency, repeating sine source so spectral changes
 * introduced by the {@code MainFilterUp} per-channel high-pass filter can be
 * measured directly. The optimizer-level tests (where the same filter sits
 * mid-pipeline) make it hard to attribute lost low-end to a specific stage;
 * these tests isolate it.
 *
 * <p>All three tests share the same source: a single 100&nbsp;Hz sine WAV
 * looped through a {@link CellList} via {@link CellFeatures#w(int, WaveData...)}.
 * The fundamental sits well below any plausible high-pass cutoff, so passing
 * audio should retain almost all of its energy. They differ only in how the
 * per-channel high-pass filter ({@link MixdownManager#enableMainFilterUp})
 * and its automation chromosome are configured.</p>
 *
 * <h2>Cases</h2>
 * <ol>
 *   <li>{@link #mainFilterUpDisabled()} &mdash; filter off; baseline
 *       passthrough RMS is recorded.</li>
 *   <li>{@link #mainFilterUpZeroChromosome()} &mdash; filter on, chromosome
 *       filled with zeros so the aggregated automation evaluates to a
 *       constant 0 and the resulting cutoff is 0&nbsp;Hz. Filter is in the
 *       compiled graph but should be inert.</li>
 *   <li>{@link #mainFilterUpRandomChromosome()} &mdash; filter on, chromosome
 *       filled with random values. Records the output for inspection but does
 *       not assert a specific RMS &mdash; this is the case the optimizer
 *       runs in.</li>
 * </ol>
 *
 * <p>Each test writes its output WAV plus an adjacent
 * {@code .spectrogram.png} so the bottom-of-spectrum behaviour can be eyeballed.</p>
 */
public class MixdownManagerFilterAutomationTest extends TestSuiteBase
		implements CellFeatures, AudioTestFeatures, RGBFeatures {

	/** Source sine frequency. Chosen low enough to be cut by even a mild HP filter. */
	private static final double SOURCE_FREQ = 100.0;

	/**
	 * Duration of each render in seconds. Set long enough that the
	 * MixdownManager.MainFilterUp aggregated automation can climb above its
	 * built-in {@code offset = -40.0} floor (the main term scales as
	 * {@code 0.1 * measures^3}); shorter durations leave the cutoff pinned at
	 * 0 Hz and the filter never actually engages.
	 */
	private static final double DURATION_SECONDS = 30.0;

	/** Number of channels feeding the mixdown. Single channel keeps assertions simple. */
	private static final int CHANNELS = 1;

	/** Number of delay layers requested from the MixdownManager. */
	private static final int DELAY_LAYERS = 2;

	/** Number of source genome parameters; oversized to accommodate any chromosome layout. */
	private static final int GENOME_PARAMS = 256;

	/** Sample rate used for the source WAV and the mixdown clock. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/**
	 * Frames per outer-loop iteration. Matches the default that
	 * {@link org.almostrealism.studio.health.StableDurationHealthComputation}
	 * uses ({@code OutputLine.sampleRate / 2}) so the compiled inner loop has
	 * the same shape as the production health-computation render path.
	 */
	private static final int BUFFER_SIZE = SAMPLE_RATE / 2;

	/**
	 * Renders a single 100&nbsp;Hz sine through MixdownManager with all
	 * post-filter stages disabled and {@link MixdownManager#enableMainFilterUp}
	 * off. This is the spectral baseline against which the other two cases
	 * are compared.
	 */
	@Test(timeout = 60_000)
	public void mainFilterUpDisabled() throws IOException {
		double rms = runMixdown("mainFilterUpDisabled", false, /* zeroGenome */ true);
		assertTrue("Output RMS for filter-off baseline should be > 0.01 (was " + rms + ")",
				rms > 0.01);
	}

	/**
	 * Renders the same source with {@link MixdownManager#enableMainFilterUp}
	 * on but the underlying genome zeroed so the aggregated automation value
	 * stays at 0 and the resulting high-pass cutoff is 0&nbsp;Hz. Verifies
	 * that the filter is wired correctly when its drive signal is constant
	 * (a 100&nbsp;Hz tone with cutoff 0&nbsp;Hz must pass).
	 */
	@Test(timeout = 60_000)
	public void mainFilterUpZeroChromosome() throws IOException {
		double baselineRms = runMixdown("mainFilterUpDisabled-baseline",
				false, /* zeroGenome */ true);
		double rms = runMixdown("mainFilterUpZeroChromosome",
				true, /* zeroGenome */ true);

		assertTrue("Output RMS with zero-chromosome HP filter should be > 0.01 (was "
				+ rms + ")", rms > 0.01);
		assertTrue("Zero-chromosome HP filter must not lose more than 25% of baseline RMS "
						+ "(baseline=" + baselineRms + ", filtered=" + rms + ")",
				rms > baselineRms * 0.75);
	}

	/**
	 * Renders the same source with the filter on and a random genome. Records
	 * RMS and writes the spectrogram for inspection but does not pin behaviour
	 * &mdash; this is the dynamic-cutoff case that the optimizer encounters.
	 */
	@Test(timeout = 60_000)
	public void mainFilterUpRandomChromosome() throws IOException {
		Options opts = new Options("mainFilterUpRandomChromosome");
		opts.enableFilterUp = true;
		opts.zeroGenome = false;
		opts.saveProfile = true;
		double rms = runMixdown(opts);
		log("Random-chromosome HP filter output RMS: " + rms);
		assertTrue("Random-chromosome HP filter output should not be silent (RMS=" + rms + ")",
				rms > 1e-6);
	}

	/**
	 * Experiment: same as {@link #mainFilterUpRandomChromosome()} but the global
	 * clock is advanced to the middle of a measure before {@code mixdown.cells(...)}
	 * is called. If the cutoff producer is being constant-folded at compile time,
	 * the frozen value should differ between this run and the baseline (which
	 * compiles with {@code clock.frame() == 0}).
	 */
	@Test(timeout = 60_000)
	public void mainFilterUpRandomPreAdvancedClock() throws IOException {
		Options opts = new Options("mainFilterUpRandomPreAdvancedClock");
		opts.enableFilterUp = true;
		opts.zeroGenome = false;
		// 30 measures at 120 BPM 4/4 == 60 s == well past the rectify floor.
		opts.preAdvanceClockFrames = 30L * (long) (Frequency.forBPM(120).l(4) * SAMPLE_RATE);
		opts.saveProfile = true;
		double rms = runMixdown(opts);
		log("Pre-advanced-clock HP filter output RMS: " + rms);
		assertTrue("Pre-advanced-clock output should not be silent (RMS=" + rms + ")",
				rms > 1e-6);
	}

	/**
	 * Configuration knobs for {@link #runMixdown(Options)}. Public so all
	 * experiment toggles are visible at the call site.
	 */
	private static class Options {
		final String name;
		boolean enableFilterUp = true;
		boolean zeroGenome = true;
		boolean saveProfile = false;
		long preAdvanceClockFrames = 0;

		Options(String name) { this.name = name; }
	}

	/**
	 * Builds the source/MixdownManager pipeline, renders for the configured
	 * duration, writes the resulting WAV and spectrogram PNG, and returns the
	 * output RMS for assertion.
	 *
	 * @param name           output file basename
	 * @param enableFilterUp value for {@link MixdownManager#enableMainFilterUp}
	 * @param zeroGenome     when {@code true} the genome is filled with zeros;
	 *                       when {@code false} it is filled with random values
	 * @return RMS of the rendered output
	 */
	private double runMixdown(String name, boolean enableFilterUp, boolean zeroGenome)
			throws IOException {
		Options opts = new Options(name);
		opts.enableFilterUp = enableFilterUp;
		opts.zeroGenome = zeroGenome;
		return runMixdown(opts);
	}

	private double runMixdown(Options opts) throws IOException {
		String name = opts.name;

		// Pin all flags to known values; only enableMainFilterUp varies between cases.
		MixdownManager.enableMainFilterUp = opts.enableFilterUp;
		MixdownManager.enableEfx = false;
		MixdownManager.enableEfxFilters = false;
		MixdownManager.enableReverb = false;
		MixdownManager.enableTransmission = false;
		MixdownManager.enableWetInAdjustment = false;
		MixdownManager.enableMasterFilterDown = false;
		MixdownManager.enableMixdown = false;
		MixdownManager.enableSourcesOnly = false;
		MixdownManager.disableClean = false;
		MixdownManager.enableAutomationManager = true;

		OperationProfileNode profile = null;
		if (opts.saveProfile) {
			profile = new OperationProfileNode(name);
			Hardware.getLocalHardware().assignProfile(profile);
		}

		try {
			double measureDuration = Frequency.forBPM(120).l(4);
			GlobalTimeManager time = new GlobalTimeManager(
					measure -> (int) (measure * measureDuration * SAMPLE_RATE));

			ProjectedGenome genome = new ProjectedGenome(GENOME_PARAMS);
			AutomationManager automation = new AutomationManager(
					genome.addChromosome(), time.getClock(),
					() -> measureDuration, SAMPLE_RATE);
			MixdownManager mixdown = new MixdownManager(genome.addChromosome(),
					CHANNELS, DELAY_LAYERS, automation, time.getClock(), SAMPLE_RATE);

			// Mirror what AudioScene's constructor does: consolidate gene values
			// once all chromosomes have been added, so refreshValues writes through
			// the shared buffer that the compiled graph references.
			genome.consolidateGeneValues();

			PackedCollection params = new PackedCollection(GENOME_PARAMS);
			if (opts.zeroGenome) {
				params.fill(0.0);
			} else {
				params.randFill();
			}
			genome.assignTo(params);

			logMainFilterUpGeneLoci(name, mixdown);

			File outFile = new File("results/" + name + ".wav");
			outFile.getParentFile().mkdirs();
			WaveOutput mixOut = new WaveOutput(outFile);
			MultiChannelAudioOutput output = new MultiChannelAudioOutput(mixOut);

			File sourceWav = getTestWavFile(SOURCE_FREQ, 2.0);
			CellList sources = w(0, c(0.0), c(1.0), WaveData.load(sourceWav));

			// Run setup BEFORE the cell graph is built so the AutomationManager
			// scale collection is initialised. If the experiment requested it,
			// also pre-advance the clock so its frame() reads non-zero at the
			// moment mixdown.cells builds its filter producers.
			OperationList setup = new OperationList(name + " setup");
			setup.add(automation.setup());
			setup.add(mixdown.setup());
			setup.add(time.setup());
			setup.get().run();

			if (opts.preAdvanceClockFrames > 0) {
				time.getClock().setFrame(opts.preAdvanceClockFrames);
				log(name + ": pre-advanced clock to frame=" + opts.preAdvanceClockFrames);
			}

			CellList graph = mixdown.cells(sources, output,
					ChannelInfo.StereoChannel.LEFT);
			graph.addRequirement(time::tick);

			// Mirror the production render path used by
			// StableDurationHealthComputation + AudioScene.runnerRealTime:
			// compile a single inner loop of BUFFER_SIZE frames, then drive
			// it from a small Java outer loop that batches up to totalFrames.
			int totalFrames = (int) (DURATION_SECONDS * SAMPLE_RATE);
			int batches = totalFrames / BUFFER_SIZE;

			graph.setup().get().run();
			Runnable batchTick = loop(graph.tick(), BUFFER_SIZE).get();
			for (int b = 0; b < batches; b++) batchTick.run();
			mixOut.write().get().run();

			double rms = computeRms(outFile);
			log(name + ": output RMS=" + rms);

			writeSpectrogram(outFile);
			return rms;
		} finally {
			if (profile != null) {
				try {
					File profileFile = new File("results/" + name + ".profile.xml");
					profileFile.getParentFile().mkdirs();
					profile.save(profileFile.getPath());
					log(name + ": wrote profile " + profileFile.getPath());
				} catch (IOException e) {
					warn("Failed to save profile: " + e.getMessage());
				}
				Hardware.getLocalHardware().assignProfile(null);
			}
		}
	}

	/**
	 * Diagnostic: prints the per-locus values of the {@code mainFilterUpSimple}
	 * chromosome's gene 0 so we can confirm the genome assignment actually
	 * reaches the gene values used by the high-pass filter.
	 */
	@SuppressWarnings("unchecked")
	private void logMainFilterUpGeneLoci(String name, MixdownManager mixdown) {
		try {
			Field f = MixdownManager.class.getDeclaredField("mainFilterUpSimple");
			f.setAccessible(true);
			Chromosome<PackedCollection> chrom =
					(Chromosome<PackedCollection>) f.get(mixdown);
			Gene<PackedCollection> gene = chrom.valueAt(0);
			StringBuilder sb = new StringBuilder(name).append(" mainFilterUp[ch=0] loci: [");
			for (int i = 0; i < AutomationManager.GENE_LENGTH; i++) {
				Factor<PackedCollection> factor = gene.valueAt(i);
				double v = factor.getResultant(c(1.0)).get().evaluate().toDouble(0);
				if (i > 0) sb.append(", ");
				sb.append(String.format("%.4f", v));
			}
			sb.append("]");
			log(sb.toString());
		} catch (Exception e) {
			log("Gene introspection failed: " + e.getMessage());
		}
	}

	/** Loads the rendered WAV and computes RMS over channel 0. */
	private double computeRms(File wavFile) throws IOException {
		WaveData wav = WaveData.load(wavFile);
		try {
			PackedCollection data = wav.getData();
			int n = data.getMemLength();
			double sumSq = 0;
			for (int i = 0; i < n; i++) {
				double v = data.toDouble(i);
				sumSq += v * v;
			}
			return Math.sqrt(sumSq / Math.max(n, 1));
		} finally {
			wav.destroy();
		}
	}

	/** Writes a spectrogram PNG next to the rendered WAV. */
	private void writeSpectrogram(File wavFile) {
		try {
			WaveData wav = WaveData.load(wavFile);
			PackedCollection spectrogram = wav.spectrogram(0);
			File png = new File(wavFile.getParentFile(),
					wavFile.getName().replace(".wav", ".spectrogram.png"));
			saveRgb(png.getPath(), cp(spectrogram)).get().run();
			spectrogram.destroy();
			wav.destroy();
		} catch (Exception e) {
			log("Failed to write spectrogram for " + wavFile + ": " + e.getMessage());
		}
	}
}
