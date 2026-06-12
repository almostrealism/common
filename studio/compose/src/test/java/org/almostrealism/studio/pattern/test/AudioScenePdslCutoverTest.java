/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.pattern.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.arrange.MixdownManagerPdslAdapter;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/**
 * Real-sample A/B validation and review-artifact generation for the PDSL mixdown cutover.
 *
 * <p>Two tests:</p>
 * <ul>
 *   <li>{@link #realSceneAbReview} — the review artifact. Loads the <em>real</em> curated
 *       arrangement ({@code pattern-factory.json} + the recursive sample library), renders
 *       the whole multi-channel scene for {@link #REVIEW_SECONDS} seconds through both the
 *       CellList and PDSL DSP paths, writes named WAVs plus a sample manifest, and asserts
 *       each render is finite and non-silent. This is what to listen to.</li>
 *   <li>{@link #pdslVsCellListPlumbingSmoke} — a fast, library-free plumbing gate. It uses
 *       the test base's scene, which falls back to <em>synthetic</em> samples when the
 *       curated library is absent, so it only proves both code paths run end-to-end and
 *       produce finite, non-silent output — <strong>not</strong> that they sound musical.</li>
 * </ul>
 *
 * <p>The two DSP paths are not yet sample-for-sample identical; see
 * {@link AudioSceneRealtimeRunner#createPdsl} for the accepted wire-first parity gaps.</p>
 *
 * @see AudioSceneRealtimeRunner
 * @see org.almostrealism.studio.optimize.AudioSceneOptimizer#createScene()
 */
public class AudioScenePdslCutoverTest extends AudioSceneTestBase {

	/** Number of source channels in the synthetic smoke scene (>= 2 for the PDSL concat). */
	private static final int SMOKE_SOURCE_COUNT = 2;

	/** Duration rendered through each path in the synthetic smoke test. */
	private static final double SMOKE_SECONDS = 2.0;

	/** Duration rendered through each path in the real-scene review (hear automation evolve). */
	private static final double REVIEW_SECONDS = 40.0;

	/** Buffer size for the review render (matches RealtimeContinuousRenderer's default). */
	private static final int REVIEW_BUFFER = 8192;

	/** Shorter duration for the stage-bisection diagnostic (enough to hear the character). */
	private static final double MUD_SECONDS = 10.0;

	/** Total measures rendered in the review scene. */
	private static final int REVIEW_MEASURES = 64;

	/** Tempo for the review scene. */
	private static final double REVIEW_BPM = 120.0;

	/** Minimum peak amplitude below which output is considered silent. */
	private static final double SILENCE_THRESHOLD = 1e-4;

	/**
	 * Renders the real curated arrangement for {@link #REVIEW_SECONDS} seconds through both
	 * the CellList and PDSL DSP paths, writes named WAVs and a sample manifest, and asserts
	 * each render is finite and non-silent.
	 *
	 * @throws IOException if the scene cannot be loaded or a WAV cannot be read back
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void realSceneAbReview() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping realSceneAbReview - need the curated library (" + SAMPLES_PATH
					+ ") and pattern factory (" + PATTERN_FACTORY + ")");
			return;
		}

		// Mirror MixdownManagerPdslVerificationTest's flag configuration so buildArgsMap can
		// read the main-filter-up genes. Leave AR_PATTERN_BATCHED unset: the per-note pattern
		// path is the correct one for real scenes (batched real-scene dispatch is still open),
		// and pattern preparation is shared by both DSP paths, so the A/B isolates the DSP.
		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		// The control render must be the production configuration — enableReverb defaults to
		// true on master, so the CellList baseline renders with its reverb bus intact. The PDSL
		// path renders its own reverb send arm regardless of this flag.
		MixdownManager.enableReverb = true;
		PatternSystemManager.enableWarnings = false;

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();

		// ONE scene, ONE genome assignment, rendered through both DSP paths. Building a
		// separate scene per path and re-applying the same seed does NOT reproduce the same
		// arrangement: scene construction uses unseeded randomness (chromosome factories), so
		// the seed only determines the projected parameter vector, not the chromosome
		// structure it maps onto. Sharing the scene guarantees both paths render the identical
		// pattern, which is the precondition for a meaningful DSP A/B.
		AudioScene<?> scene = loadRealScene(library, patternFactory);
		int channelCount = scene.getChannelCount();
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found in the real arrangement", seed >= 0);
		applyGenome(scene, seed);
		writeManifest(scene, channelCount, seed, new File(outDir, "manifest.txt"));

		int frames = (int) (REVIEW_SECONDS * SAMPLE_RATE);
		renderBothPaths(scene, frames, REVIEW_BUFFER, outDir, "review", channelCount + "ch");
	}

	/**
	 * Fast plumbing gate: renders the test-base scene (synthetic samples when the curated
	 * library is absent) through both DSP paths and asserts finite, non-silent output. This
	 * validates the code paths, not musical content.
	 *
	 * @throws IOException if a rendered WAV cannot be read back
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void pdslVsCellListPlumbingSmoke() throws IOException {
		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		PatternSystemManager.enableWarnings = false;

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();

		File samplesDir = getSamplesDir();
		AudioScene<?> scene = createBaselineScene(samplesDir, SMOKE_SOURCE_COUNT);
		long seed = findWorkingGenomeSeed(scene, samplesDir);
		Assert.assertTrue("No working genome found for smoke scene", seed >= 0);
		applyGenome(scene, seed);

		int frames = (int) (SMOKE_SECONDS * SAMPLE_RATE);
		renderBothPaths(scene, frames, AudioScene.DEFAULT_REALTIME_BUFFER_SIZE,
				outDir, "smoke", SMOKE_SOURCE_COUNT + "ch");
	}

	/**
	 * Renders the same scene (same genome, same pattern) through both DSP paths — CellList
	 * (flag off) then PDSL Block-forward (flag on) — writing a named WAV for each and
	 * asserting each is finite and non-silent.
	 *
	 * @param scene        the shared scene (genome already applied)
	 * @param frames       number of frames to render per path
	 * @param bufferSize   frames per buffer
	 * @param outDir       output directory
	 * @param prefix       file name prefix ("review" / "smoke")
	 * @param channelLabel channel descriptor for the file name (e.g. "6ch")
	 * @throws IOException if a rendered WAV cannot be read back
	 */
	private void renderBothPaths(AudioScene<?> scene, int frames, int bufferSize,
								 File outDir, String prefix, String channelLabel) throws IOException {
		boolean previous = MixdownManager.enablePdslMixdown;
		try {
			MixdownManager.enablePdslMixdown = false;
			double[] cellList = renderAndRead(scene, null, frames, bufferSize,
					new File(outDir, prefix + "_celllist_" + channelLabel + ".wav"));
			assertFiniteNonSilent(prefix + " CellList", cellList);

			MixdownManager.enablePdslMixdown = true;
			double[] pdsl = renderAndRead(scene, null, frames, bufferSize,
					new File(outDir, prefix + "_pdsl_" + channelLabel + ".wav"));
			assertFiniteNonSilent(prefix + " PDSL", pdsl);

			reportWindowedParity(prefix, cellList, pdsl);
		} finally {
			MixdownManager.enablePdslMixdown = previous;
		}
	}

	/**
	 * Fast no-render diagnostic: loads the real scene exactly as the review/bisection do and logs
	 * its actual channel configuration (wet and reverb channels), so we can verify the scene is
	 * configured like production (default settings send channels 1-5 to reverb, 2-5 wet) rather
	 * than an empty/misconfigured scene.
	 *
	 * @throws IOException if the scene cannot be loaded
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void logSceneChannelConfig() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping logSceneChannelConfig - need the curated library and pattern factory");
			return;
		}

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		log("channelCount=" + scene.getChannelCount());
		log("reverbChannels=" + scene.getMixdownManager().getReverbChannels());
		log("wetChannels=" + scene.getEfxManager().getWetChannels());
	}

	/**
	 * No-render diagnostic that evaluates the actual production automation producers built by
	 * {@link MixdownManagerPdslAdapter#buildArgsMap} — most importantly {@code volume} — at a
	 * series of clock frames. This localizes the PDSL silence: if {@code volume} evaluates to
	 * ~0 while the filter-cutoff automations evaluate to sane values, the per-channel volume
	 * gene (not the DSP wiring) is the cause; if every clock-driven automation is ~0, the
	 * issue is the standalone clock/automation setup rather than the gene projection.
	 *
	 * @throws IOException if the curated library cannot be read
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void logAutomationProducerValues() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping logAutomationProducerValues - need the curated library and pattern factory");
			return;
		}

		MixdownManager.enableEfx = true;
		MixdownManager.enablePdslMixdown = true;
		MixdownManager.enableMainFilterUp = true;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		int channelCount = scene.getChannelCount();
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found in the real arrangement", seed >= 0);
		applyGenome(scene, seed);

		// Build and run the real runner's setup so the AutomationManager state matches the
		// render context (getAggregatedValue is otherwise uninitialized and reads as NaN). The
		// runner is otherwise unused here — we only need the setup side effects.
		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();
		WaveOutput sink = new WaveOutput(() -> new File(outDir, "automation_probe_sink.wav"), 24, true);
		MultiChannelAudioOutput output = new MultiChannelAudioOutput(sink);
		TemporalCellular runner = scene.runnerRealTime(output, null, 8192);
		runner.setup().get().run();

		MixdownManager mixdown = scene.getMixdownManager();
		MixdownManagerPdslAdapter.Config config = new MixdownManagerPdslAdapter.Config(
				channelCount, 8192, SAMPLE_RATE, 40, 0.5, 6500);
		Map<String, Object> args = MixdownManagerPdslAdapter.buildArgsMap(
				mixdown, scene.getEfxManager(), config);

		// The time-varying values are now collection SLOTS refreshed per buffer; run the
		// runner's refresh op at each probed clock frame and read the slots back, so this
		// validates the exact mechanism the real-time tick uses (compiled refresh ops
		// tracking the clock), not just standalone producer evaluation.
		Runnable refresh = MixdownManagerPdslAdapter.automationRefresh(
				mixdown, scene.getEfxManager(), config, args).get();
		String[] slotNames = {"volume", "hp_cutoff", "lp_cutoff", "efx_automation", "reverb_send"};
		int[] slotFrames = {0, 441000, 661500, 882000, 1102500, 1323000, 1764000};
		for (int frame : slotFrames) {
			scene.getTimeManager().getClock().setFrame(frame);
			refresh.run();
			for (String name : slotNames) {
				PackedCollection slot = (PackedCollection) args.get(name);
				StringBuilder sb = new StringBuilder("slot " + name + " frame=" + frame + " ->");
				for (int i = 0; i < slot.getMemLength(); i++) {
					sb.append(' ').append(slot.toDouble(i));
				}
				log(sb.toString());
			}
		}

		String[] names = {"efx_wet_level",
				"transmission", "efx_fb_transmission", "efx_fb_passthrough"};
		int[] frames = {0, 441000};

		log("channelCount=" + channelCount + " seed=" + seed);
		for (String name : names) {
			Object value = args.get(name);
			if (!(value instanceof Producer)) {
				log(name + " is not a Producer (type=" + (value == null ? "null"
						: value.getClass().getSimpleName()) + ")");
				continue;
			}

			Evaluable<PackedCollection> evaluable;
			try {
				evaluable = ((Producer<PackedCollection>) value).get();
			} catch (Exception e) {
				log(name + " compile failed: " + e);
				continue;
			}

			for (int frame : frames) {
				scene.getTimeManager().getClock().setFrame(frame);
				try {
					PackedCollection result = evaluable.evaluate();
					StringBuilder sb = new StringBuilder(name + " frame=" + frame + " ->");
					for (int i = 0; i < result.getMemLength(); i++) {
						sb.append(' ').append(result.toDouble(i));
					}
					log(sb.toString());
				} catch (Exception e) {
					log(name + " frame=" + frame + " evaluate failed: " + e);
				}
			}
		}
	}

	/**
	 * Dumps the raw automation-gene component values (phase 0-2, magnitude 3-5) of the
	 * {@code mainFilterUp} and {@code volume} genes for channel 0, for a fixed genome seed. This
	 * distinguishes the two candidate causes of the disabled PDSL main-arm high-pass: if the
	 * mainFilterUp magnitudes are ~0, the genome itself disables the filter (so the divergence is
	 * the IIR-vs-FIR behaviour at near-zero cutoff); if they are non-zero, the adapter's
	 * {@code getAggregatedValue} read diverges from the Java CellList path. No render needed — the
	 * gene values are deterministic from the seed.
	 *
	 * @throws IOException if the scene cannot be loaded
	 */
	@Test(timeout = 300_000)
	@TestDepth(2)
	public void logMainFilterUpGene() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping logMainFilterUpGene - need the curated library and pattern factory");
			return;
		}

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		applyGenome(scene, 46);
		MixdownManager mixdown = scene.getMixdownManager();

		logGeneComponents("mainFilterUp",
				MixdownManagerPdslAdapter.geneComponents(
						MixdownManagerPdslAdapter.mainFilterUpGene(mixdown), 0));
		logGeneComponents("volume",
				MixdownManagerPdslAdapter.geneComponents(
						MixdownManagerPdslAdapter.volumeGene(mixdown), 0));
	}

	/**
	 * Logs the six evaluated component values of an automation gene for channel 0.
	 *
	 * @param name       label for the gene
	 * @param components the six component producers
	 */
	private void logGeneComponents(String name, Producer<PackedCollection>[] components) {
		StringBuilder sb = new StringBuilder("gene " + name + " ch0 components ->");
		for (Producer<PackedCollection> component : components) {
			try {
				sb.append(' ').append(component.get().evaluate().toDouble(0));
			} catch (Exception e) {
				sb.append(" ERR");
			}
		}
		log(sb.toString());
	}

	/**
	 * Stage-bisection diagnostic for the PDSL "mud" vs the clean CellList render. Renders the
	 * SAME scene and genome through: the CellList reference; the full PDSL path; PDSL with the
	 * reverb bus disabled; and PDSL with both reverb and the efx feedback grid disabled. Writes
	 * a named WAV for each and logs peak/RMS so the offending stage can be localized by ear and
	 * by level. Uses a short clip so all four renders fit one test-runner window.
	 *
	 * @throws IOException if the scene cannot be loaded or a WAV read back
	 */
	@Test(timeout = 1_080_000)
	@TestDepth(2)
	public void pdslMudBisection() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping pdslMudBisection - need the curated library and pattern factory");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		PatternSystemManager.enableWarnings = false;

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found in the real arrangement", seed >= 0);
		applyGenome(scene, seed);

		int frames = (int) (MUD_SECONDS * SAMPLE_RATE);
		double prevReverb = MixdownManagerPdslAdapter.reverbSend;
		double prevFeedback = MixdownManagerPdslAdapter.feedbackGain;
		boolean previous = MixdownManager.enablePdslMixdown;
		boolean prevEnableReverb = MixdownManager.enableReverb;
		try {
			// Java reference, reverb OFF then ON: the (on - off) difference is how much the Java
			// reverb actually contributes for this genome — the yardstick for the PDSL reverb.
			MixdownManager.enablePdslMixdown = false;
			MixdownManager.enableReverb = false;
			reportLevels("celllist-reverb-off", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "mud_celllist_reverboff.wav")));

			MixdownManager.enableReverb = true;
			reportLevels("celllist-reverb-on", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "mud_celllist_reverbon.wav")));

			// PDSL reverb on (reverbChannels drive the send) then off (send trim 0). The PDSL
			// reverb is independent of MixdownManager.enableReverb.
			MixdownManager.enablePdslMixdown = true;
			MixdownManagerPdslAdapter.reverbSend = prevReverb;
			MixdownManagerPdslAdapter.feedbackGain = prevFeedback;
			reportLevels("pdsl-reverb-on", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "mud_pdsl_reverbon.wav")));

			MixdownManagerPdslAdapter.reverbSend = 0.0;
			reportLevels("pdsl-reverb-off", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "mud_pdsl_reverboff.wav")));
		} finally {
			MixdownManagerPdslAdapter.reverbSend = prevReverb;
			MixdownManagerPdslAdapter.feedbackGain = prevFeedback;
			MixdownManager.enablePdslMixdown = previous;
			MixdownManager.enableReverb = prevEnableReverb;
		}
	}

	/**
	 * Arm-isolation diagnostic for the PDSL-vs-CellList level divergence. Renders the SAME scene
	 * and genome and measures, for both paths, the main (dry) bus alone and the efx (wet) bus
	 * alone, so the ~2x PDSL hotness can be attributed to a specific arm. CellList main-only is
	 * obtained with {@code enableEfx=false} (Java's main bus is identical with efx on or off:
	 * {@code main = HP(dry) x v}); PDSL main-only/efx-only use the {@code mainArmGain}/
	 * {@code efxArmGain} toggles on {@code mixdown_master_wet} (so the consolidated MAIN+WET
	 * buffer layout is unchanged). Reverb is held off throughout.
	 *
	 * @throws IOException if the scene cannot be loaded or a WAV read back
	 */
	@Test(timeout = 1_200_000)
	@TestDepth(2)
	public void pdslArmIsolation() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping pdslArmIsolation - need the curated library and pattern factory");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		PatternSystemManager.enableWarnings = false;

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found in the real arrangement", seed >= 0);
		applyGenome(scene, seed);

		int frames = (int) (MUD_SECONDS * SAMPLE_RATE);
		boolean prevPdsl = MixdownManager.enablePdslMixdown;
		boolean prevEfx = MixdownManager.enableEfx;
		double prevReverbSend = MixdownManagerPdslAdapter.reverbSend;
		double prevMain = MixdownManagerPdslAdapter.mainArmGain;
		double prevEfxGain = MixdownManagerPdslAdapter.efxArmGain;
		double prevReverbGain = MixdownManagerPdslAdapter.reverbArmGain;
		try {
			// --- CellList references (Java) ---
			MixdownManager.enablePdslMixdown = false;
			MixdownManager.enableEfx = true;
			reportLevels("celllist-full", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "arm_celllist_full.wav")));

			MixdownManager.enableEfx = false;
			reportLevels("celllist-main-only", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "arm_celllist_main.wav")));

			// --- PDSL variants (efx must stay on so the WET region is rendered) ---
			MixdownManager.enablePdslMixdown = true;
			MixdownManager.enableEfx = true;
			MixdownManagerPdslAdapter.reverbSend = 0.0;

			MixdownManagerPdslAdapter.mainArmGain = 1.0;
			MixdownManagerPdslAdapter.efxArmGain = 1.0;
			MixdownManagerPdslAdapter.reverbArmGain = 1.0;
			reportLevels("pdsl-full", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "arm_pdsl_full.wav")));

			MixdownManagerPdslAdapter.efxArmGain = 0.0;
			MixdownManagerPdslAdapter.reverbArmGain = 0.0;
			reportLevels("pdsl-main-only", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "arm_pdsl_main.wav")));

			MixdownManagerPdslAdapter.mainArmGain = 0.0;
			MixdownManagerPdslAdapter.efxArmGain = 1.0;
			MixdownManagerPdslAdapter.reverbArmGain = 0.0;
			reportLevels("pdsl-efx-only", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "arm_pdsl_efx.wav")));
		} finally {
			MixdownManager.enablePdslMixdown = prevPdsl;
			MixdownManager.enableEfx = prevEfx;
			MixdownManagerPdslAdapter.reverbSend = prevReverbSend;
			MixdownManagerPdslAdapter.mainArmGain = prevMain;
			MixdownManagerPdslAdapter.efxArmGain = prevEfxGain;
			MixdownManagerPdslAdapter.reverbArmGain = prevReverbGain;
		}
	}

	/**
	 * Reads the consolidated render buffer row-by-row on a FRESH JVM after setup and a few
	 * ticks of the PDSL runner, logging per-region peak/RMS. The first PDSL render of a JVM
	 * emits a full-scale burst that later renders do not; if some region rows show huge or
	 * non-finite values here, the burst is uninitialized buffer content reaching the model
	 * input (rows are [LEFT-MAIN(N), LEFT-WET(N), RIGHT-MAIN(N), RIGHT-WET(N)]).
	 *
	 * @throws IOException if the scene cannot be loaded
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void logConsolidatedRegionLevels() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping logConsolidatedRegionLevels - need the curated library and pattern factory");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		PatternSystemManager.enableWarnings = false;

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found", seed >= 0);
		applyGenome(scene, seed);

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();
		boolean prevPdsl = MixdownManager.enablePdslMixdown;
		try {
			MixdownManager.enablePdslMixdown = true;
			WaveOutput sink = new WaveOutput(() -> new File(outDir, "region_probe_sink.wav"), 24, true);
			MultiChannelAudioOutput output = new MultiChannelAudioOutput(sink);
			TemporalCellular runner = scene.runnerRealTime(output, null, REVIEW_BUFFER);
			runner.setup().get().run();

			int channelCount = scene.getChannelCount();
			Runnable tick = runner.tick().get();
			for (int t = 0; t < 3; t++) {
				tick.run();
				log("clock frame after tick " + t + ": "
						+ scene.getTimeManager().getClock().getFrame()
						+ " (expected " + ((t + 1) * (long) REVIEW_BUFFER) + ")");
				PackedCollection consolidated = scene.getConsolidatedRenderBuffer();
				int regions = consolidated.getMemLength() / REVIEW_BUFFER;
				double[][] rows = new double[regions][];
				for (int r = 0; r < regions; r++) {
					double[] row = consolidated.toArray(r * REVIEW_BUFFER, REVIEW_BUFFER);
					rows[r] = row;
					double peak = 0.0;
					double energy = 0.0;
					int nonFinite = 0;
					for (double v : row) {
						if (!Double.isFinite(v)) {
							nonFinite++;
							continue;
						}
						peak = Math.max(peak, Math.abs(v));
						energy += v * v;
					}
					log(String.format("region tick=%d row=%d peak=%.6g rms=%.6g nonFinite=%d",
							t, r, peak, Math.sqrt(energy / REVIEW_BUFFER), nonFinite));
				}

				// Reference main-bus computation, mirroring the Java chain at the genome's
				// near-zero cutoff (identity filter passband): per channel clamp(+-0.99) ->
				// x volume -> sum -> clamp(+-0.99) -> x masterBusGain. Computed once with the
				// per-channel input clamp and once without it, so the rendered CellList and
				// PDSL outputs can each be matched against the variant they implement.
				double volume = 1.0 / 3.0;
				double clampedEnergy = 0.0;
				double unclampedEnergy = 0.0;
				for (int i = 0; i < REVIEW_BUFFER; i++) {
					double sumClamped = 0.0;
					double sumRaw = 0.0;
					for (int ch = 0; ch < channelCount; ch++) {
						double v = rows[ch][i];
						sumClamped += volume * Math.max(-0.99, Math.min(0.99, v));
						sumRaw += volume * v;
					}
					double refClamped = 0.5 * Math.max(-0.99, Math.min(0.99, sumClamped));
					double refRaw = 0.5 * Math.max(-0.99, Math.min(0.99, sumRaw));
					clampedEnergy += refClamped * refClamped;
					unclampedEnergy += refRaw * refRaw;
				}
				log(String.format("mainref tick=%d rmsClamped=%.6g rmsUnclamped=%.6g",
						t, Math.sqrt(clampedEnergy / REVIEW_BUFFER),
						Math.sqrt(unclampedEnergy / REVIEW_BUFFER)));

				// Dump the MAIN rows for offline sample-level comparison against the
				// rendered master waveforms (text, one row per line).
				StringBuilder dump = new StringBuilder();
				for (int ch = 0; ch < channelCount; ch++) {
					for (int i = 0; i < REVIEW_BUFFER; i++) {
						if (i > 0) dump.append(' ');
						dump.append(rows[ch][i]);
					}
					dump.append('\n');
				}
				Files.write(new File(outDir,
						"region_rows_tick" + t + ".txt").toPath(),
						dump.toString().getBytes(StandardCharsets.UTF_8));
			}

			// Fast-forward the clock to 25s and render two more buffers. At 25s the gene
			// high-pass cutoffs are in the 5-20 kHz range, so IF the per-buffer automation
			// refresh reaches the compiled model, buffers 3-4 of the sink must be radically
			// quieter/thinner than buffers 0-2 (identical pattern content, swept filter).
			scene.getTimeManager().getClock().setFrame(1102500);
			tick.run();
			tick.run();
			sink.write().get().run();

			double[] sinkSamples = readWavMono(new File(outDir, "region_probe_sink.wav"));
			for (int b = 0; b < 5; b++) {
				int start = b * REVIEW_BUFFER;
				int end = Math.min(sinkSamples.length, (b + 1) * REVIEW_BUFFER);
				if (start >= end) break;
				log(String.format("sweepcheck buffer=%d rms=%.5f%s", b,
						windowRms(sinkSamples, start, end),
						b >= 3 ? " (clock at 25s; expect much lower if sweep engages)" : ""));
			}
			runner.reset();
		} finally {
			MixdownManager.enablePdslMixdown = prevPdsl;
		}
	}

	/**
	 * Isolates the PDSL efx (wet) arm in a FRESH JVM, first render, so the measurement is not
	 * perturbed by earlier renders in the same process (the efx arm has measured wildly
	 * differently — near-zero vs instantly saturated — depending only on JVM render history).
	 * Renders the efx arm alone with the feedback grid active, then with feedback disabled
	 * ({@code feedbackGain = 0}), localizing any runaway to the feedback stage versus the
	 * feedforward chain.
	 *
	 * @throws IOException if the scene cannot be loaded or a WAV read back
	 */
	@Test(timeout = 900_000)
	@TestDepth(2)
	public void pdslEfxArmProbe() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping pdslEfxArmProbe - need the curated library and pattern factory");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		PatternSystemManager.enableWarnings = false;

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found", seed >= 0);
		applyGenome(scene, seed);

		int frames = (int) (MUD_SECONDS * SAMPLE_RATE);
		boolean prevPdsl = MixdownManager.enablePdslMixdown;
		double prevReverbSend = MixdownManagerPdslAdapter.reverbSend;
		double prevMainGain = MixdownManagerPdslAdapter.mainArmGain;
		double prevFeedback = MixdownManagerPdslAdapter.feedbackGain;
		try {
			// Feedback-off FIRST, feedback-on SECOND: the previous ordering (fb first) saturated
			// on the first render of the JVM; if fb-second saturates too, JVM render history is
			// not the trigger and the runaway is robust within the full-model context.
			MixdownManager.enablePdslMixdown = true;
			MixdownManagerPdslAdapter.reverbSend = 0.0;
			MixdownManagerPdslAdapter.mainArmGain = 0.0;
			MixdownManagerPdslAdapter.reverbArmGain = 0.0;
			MixdownManagerPdslAdapter.efxArmGain = 1.0;
			MixdownManagerPdslAdapter.feedbackGain = 0.0;
			reportLevels("pdsl-efx-only-nofb", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "efxprobe_nofb.wav")));

			MixdownManagerPdslAdapter.feedbackGain = prevFeedback;
			reportLevels("pdsl-efx-only-fb", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "efxprobe_fb.wav")));
		} finally {
			MixdownManager.enablePdslMixdown = prevPdsl;
			MixdownManagerPdslAdapter.reverbSend = prevReverbSend;
			MixdownManagerPdslAdapter.mainArmGain = prevMainGain;
			MixdownManagerPdslAdapter.feedbackGain = prevFeedback;
			MixdownManagerPdslAdapter.efxArmGain = 1.0;
			MixdownManagerPdslAdapter.reverbArmGain = 1.0;
		}
	}

	/**
	 * Minimal 3-render arm localizer (stays under the cross-render native-memory limit that
	 * crashes the 5-render {@link #pdslArmIsolation}). Renders, for the same scene/genome:
	 * CellList full (Java reference), PDSL full (all arms), and PDSL main-only (efx and reverb
	 * arms zeroed). If PDSL main-only ≈ PDSL full, the hot component is the main/dry arm; if
	 * PDSL main-only is much smaller than PDSL full, the efx arm is the hot component.
	 *
	 * @throws IOException if the scene cannot be loaded or a WAV read back
	 */
	@Test(timeout = 900_000)
	@TestDepth(2)
	public void pdslMainVsEfxQuick() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping pdslMainVsEfxQuick - need the curated library and pattern factory");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		PatternSystemManager.enableWarnings = false;

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found", seed >= 0);
		applyGenome(scene, seed);

		int frames = (int) (MUD_SECONDS * SAMPLE_RATE);
		boolean prevPdsl = MixdownManager.enablePdslMixdown;
		double prevReverbSend = MixdownManagerPdslAdapter.reverbSend;
		double prevEfxGain = MixdownManagerPdslAdapter.efxArmGain;
		double prevReverbGain = MixdownManagerPdslAdapter.reverbArmGain;
		try {
			// Two renders only (cross-render native memory exhausts on the 3rd). CellList full is
			// already known from prior runs; here we localize main-vs-efx within the PDSL path.
			MixdownManager.enablePdslMixdown = true;
			MixdownManagerPdslAdapter.reverbSend = 0.0;
			MixdownManagerPdslAdapter.efxArmGain = 1.0;
			MixdownManagerPdslAdapter.reverbArmGain = 1.0;
			reportLevels("pdsl-full", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "q_pdsl_full.wav")));

			MixdownManagerPdslAdapter.efxArmGain = 0.0;
			MixdownManagerPdslAdapter.reverbArmGain = 0.0;
			reportLevels("pdsl-main-only", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "q_pdsl_main.wav")));
		} finally {
			MixdownManager.enablePdslMixdown = prevPdsl;
			MixdownManagerPdslAdapter.reverbSend = prevReverbSend;
			MixdownManagerPdslAdapter.efxArmGain = prevEfxGain;
			MixdownManagerPdslAdapter.reverbArmGain = prevReverbGain;
		}
	}

	/**
	 * Isolates the main-arm high-pass filter's contribution to the PDSL-vs-CellList level
	 * divergence. Renders the same scene/genome four ways: CellList with and without the
	 * {@code mainFilterUp} high-pass, and the PDSL main arm with and without it (via the
	 * {@code hpCutoffOverrideHz=0} bypass). If the PDSL/CellList ratio collapses to ~1 once the
	 * high-pass is removed from both, the IIR (Java) vs FIR (PDSL) high-pass is the divergence;
	 * if the ratio persists, the cause is elsewhere. Efx/reverb arms are zeroed on the PDSL side.
	 *
	 * @throws IOException if the scene cannot be loaded or a WAV read back
	 */
	@Test(timeout = 1_200_000)
	@TestDepth(2)
	public void pdslHpIsolation() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping pdslHpIsolation - need the curated library and pattern factory");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		PatternSystemManager.enableWarnings = false;

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found in the real arrangement", seed >= 0);
		applyGenome(scene, seed);

		int frames = (int) (MUD_SECONDS * SAMPLE_RATE);
		boolean prevPdsl = MixdownManager.enablePdslMixdown;
		boolean prevMainHp = MixdownManager.enableMainFilterUp;
		double prevReverbSend = MixdownManagerPdslAdapter.reverbSend;
		double prevEfxGain = MixdownManagerPdslAdapter.efxArmGain;
		double prevReverbGain = MixdownManagerPdslAdapter.reverbArmGain;
		double prevHpOverride = MixdownManagerPdslAdapter.hpCutoffOverrideHz;
		try {
			// CellList: main bus with the high-pass, then without it.
			MixdownManager.enablePdslMixdown = false;
			MixdownManager.enableMainFilterUp = true;
			reportLevels("celllist-hp", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "hp_celllist_hp.wav")));

			MixdownManager.enableMainFilterUp = false;
			reportLevels("celllist-nohp", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "hp_celllist_nohp.wav")));

			// PDSL main arm with the high-pass, then bypassed (cutoff 0 -> identity FIR).
			MixdownManager.enablePdslMixdown = true;
			MixdownManager.enableMainFilterUp = true;
			MixdownManagerPdslAdapter.reverbSend = 0.0;
			MixdownManagerPdslAdapter.efxArmGain = 0.0;
			MixdownManagerPdslAdapter.reverbArmGain = 0.0;

			MixdownManagerPdslAdapter.hpCutoffOverrideHz = -1.0;
			reportLevels("pdsl-main-hp", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "hp_pdsl_hp.wav")));

			MixdownManagerPdslAdapter.hpCutoffOverrideHz = 0.0;
			reportLevels("pdsl-main-nohp", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "hp_pdsl_nohp.wav")));
		} finally {
			MixdownManager.enablePdslMixdown = prevPdsl;
			MixdownManager.enableMainFilterUp = prevMainHp;
			MixdownManagerPdslAdapter.reverbSend = prevReverbSend;
			MixdownManagerPdslAdapter.efxArmGain = prevEfxGain;
			MixdownManagerPdslAdapter.reverbArmGain = prevReverbGain;
			MixdownManagerPdslAdapter.hpCutoffOverrideHz = prevHpOverride;
		}
	}

	/**
	 * Forces the PDSL main-arm high-pass cutoff across a sweep of fixed values and measures the
	 * resulting main-bus level. If higher forced cutoffs progressively reduce the level, the FIR
	 * high-pass is wired correctly in the main arm and the production hotness is caused by the
	 * gene-driven {@code hp_cutoff} evaluating near zero at render time (a passthrough). If the
	 * level is invariant to the forced cutoff, the high-pass is not applied in the main-arm
	 * context at all. The CellList full mix is logged as the target level.
	 *
	 * @throws IOException if the scene cannot be loaded or a WAV read back
	 */
	@Test(timeout = 1_200_000)
	@TestDepth(2)
	public void pdslHpForcedSweep() throws IOException {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping pdslHpForcedSweep - need the curated library and pattern factory");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = false;
		PatternSystemManager.enableWarnings = false;

		File outDir = new File("results/pdsl-cutover");
		outDir.mkdirs();

		AudioScene<?> scene = loadRealScene(library, patternFactory);
		long seed = findWorkingGenomeSeed(scene, library);
		Assert.assertTrue("No working genome found in the real arrangement", seed >= 0);
		applyGenome(scene, seed);

		int frames = (int) (MUD_SECONDS * SAMPLE_RATE);
		boolean prevPdsl = MixdownManager.enablePdslMixdown;
		double prevReverbSend = MixdownManagerPdslAdapter.reverbSend;
		double prevEfxGain = MixdownManagerPdslAdapter.efxArmGain;
		double prevReverbGain = MixdownManagerPdslAdapter.reverbArmGain;
		double prevHpOverride = MixdownManagerPdslAdapter.hpCutoffOverrideHz;
		try {
			MixdownManager.enablePdslMixdown = false;
			reportLevels("celllist-full-target", renderAndRead(scene, null, frames, REVIEW_BUFFER,
					new File(outDir, "hpsweep_celllist.wav")));

			MixdownManager.enablePdslMixdown = true;
			MixdownManagerPdslAdapter.reverbSend = 0.0;
			MixdownManagerPdslAdapter.efxArmGain = 0.0;
			MixdownManagerPdslAdapter.reverbArmGain = 0.0;
			for (double cutoff : new double[] {0.0, 2000.0, 6000.0, 12000.0, 18000.0}) {
				MixdownManagerPdslAdapter.hpCutoffOverrideHz = cutoff;
				reportLevels("pdsl-main hpCutoff=" + cutoff,
						renderAndRead(scene, null, frames, REVIEW_BUFFER,
								new File(outDir, "hpsweep_pdsl_" + (int) cutoff + ".wav")));
			}
		} finally {
			MixdownManager.enablePdslMixdown = prevPdsl;
			MixdownManagerPdslAdapter.reverbSend = prevReverbSend;
			MixdownManagerPdslAdapter.efxArmGain = prevEfxGain;
			MixdownManagerPdslAdapter.reverbArmGain = prevReverbGain;
			MixdownManagerPdslAdapter.hpCutoffOverrideHz = prevHpOverride;
		}
	}

	/**
	 * Logs the peak amplitude, RMS, and non-finite count of a rendered signal for the
	 * stage-bisection diagnostic.
	 *
	 * @param label   the variant label
	 * @param samples the rendered samples
	 */
	private void reportLevels(String label, double[] samples) {
		double peak = 0.0;
		double energy = 0.0;
		int nonFinite = 0;
		for (double sample : samples) {
			if (!Double.isFinite(sample)) {
				nonFinite++;
				continue;
			}
			peak = Math.max(peak, Math.abs(sample));
			energy += sample * sample;
		}
		double rms = Math.sqrt(energy / Math.max(1, samples.length));
		log(String.format("bisection stage=%s peak=%.4f rms=%.4f nonFinite=%d",
				label, peak, rms, nonFinite));
	}

	/**
	 * Logs a windowed RMS comparison of the two renders so level parity can be verified over
	 * time (the automation sweeps mean a single whole-file RMS can hide a divergence that
	 * grows over the duration). Windows are aligned sample ranges of both renders; for each,
	 * the CellList RMS, the PDSL RMS, and their ratio are logged, then the whole-file ratio.
	 *
	 * @param label    render label for the log lines
	 * @param cellList samples from the CellList (control) render
	 * @param pdsl     samples from the PDSL render
	 */
	private void reportWindowedParity(String label, double[] cellList, double[] pdsl) {
		int n = Math.min(cellList.length, pdsl.length);
		int window = Math.max(1, n / 8);
		for (int start = 0; start < n; start += window) {
			int end = Math.min(n, start + window);
			double ca = windowRms(cellList, start, end);
			double pa = windowRms(pdsl, start, end);
			log(String.format("parity %s window=%d-%d celllist=%.4f pdsl=%.4f ratio=%.3f",
					label, start, end, ca, pa, ca > 0 ? pa / ca : Double.NaN));
		}
		double cAll = windowRms(cellList, 0, n);
		double pAll = windowRms(pdsl, 0, n);
		log(String.format("parity %s overall celllist=%.4f pdsl=%.4f ratio=%.3f",
				label, cAll, pAll, cAll > 0 ? pAll / cAll : Double.NaN));
	}

	/**
	 * Loads a WAV and returns its first-channel samples.
	 *
	 * @param file the WAV file
	 * @return mono samples
	 * @throws IOException if the file cannot be read
	 */
	private double[] readWavMono(File file) throws IOException {
		WaveData wav = WaveData.load(file);
		try {
			PackedCollection data = wav.getData();
			int n = data.getMemLength();
			double[] samples = new double[n];
			for (int i = 0; i < n; i++) {
				samples[i] = data.toDouble(i);
			}
			return samples;
		} finally {
			wav.destroy();
		}
	}

	/**
	 * Computes the RMS of one sample range.
	 *
	 * @param samples the samples
	 * @param start   inclusive start index
	 * @param end     exclusive end index
	 * @return the root-mean-square of the range
	 */
	private double windowRms(double[] samples, int start, int end) {
		double energy = 0.0;
		for (int i = start; i < end; i++) {
			if (Double.isFinite(samples[i])) energy += samples[i] * samples[i];
		}
		return Math.sqrt(energy / Math.max(1, end - start));
	}

	/**
	 * Loads the real curated arrangement at the review tempo and length via
	 * {@link #loadCuratedScene(File, File, double, int)}.
	 *
	 * @param library        the recursive sample library root
	 * @param patternFactory the curated pattern factory JSON
	 * @return a freshly loaded scene
	 * @throws IOException if the scene cannot be loaded
	 */
	private AudioScene<?> loadRealScene(File library, File patternFactory) throws IOException {
		return loadCuratedScene(library, patternFactory, REVIEW_BPM, REVIEW_MEASURES);
	}

	/**
	 * Builds a real-time runner over the scene (respecting {@link MixdownManager#enablePdslMixdown}),
	 * ticks it for {@code frames} frames, writes the WAV, and reads the samples back.
	 *
	 * @param scene      the scene to render
	 * @param channels   channel indices, or {@code null} for all channels
	 * @param frames     number of frames to render
	 * @param bufferSize frames per buffer
	 * @param outFile    destination WAV file
	 * @return the rendered samples
	 * @throws IOException if the WAV cannot be read back
	 */
	private double[] renderAndRead(AudioScene<?> scene, List<Integer> channels, int frames,
								   int bufferSize, File outFile) throws IOException {
		WaveOutput out = new WaveOutput(() -> outFile, 24, true);
		MultiChannelAudioOutput output = new MultiChannelAudioOutput(out);
		TemporalCellular runner = scene.runnerRealTime(output, channels, bufferSize);

		int bufferCount = (frames + bufferSize - 1) / bufferSize;
		Runnable setup = runner.setup().get();
		Runnable tick = runner.tick().get();
		try {
			setup.run();
			for (int b = 0; b < bufferCount; b++) {
				tick.run();
			}
			out.write().get().run();
		} finally {
			out.reset();
			runner.reset();
		}

		WaveData wav = WaveData.load(outFile);
		try {
			PackedCollection data = wav.getData();
			int n = data.getMemLength();
			double[] samples = new double[n];
			for (int i = 0; i < n; i++) {
				samples[i] = data.toDouble(i);
			}
			return samples;
		} finally {
			wav.destroy();
		}
	}

	/**
	 * Writes a human-readable manifest of the channel-to-instrument-to-sample mapping for the
	 * loaded arrangement, so a reviewer knows what to expect to hear (drums, bass, synths, ...).
	 *
	 * @param scene        the loaded scene
	 * @param channelCount the number of active channels
	 * @param seed         the genome seed applied for the review render
	 * @param manifestFile destination manifest file
	 * @throws IOException if the manifest cannot be written
	 */
	private void writeManifest(AudioScene<?> scene, int channelCount, long seed, File manifestFile)
			throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("PDSL cutover review render\n");
		sb.append("channels=").append(channelCount)
				.append(" measures=").append(REVIEW_MEASURES)
				.append(" seconds=").append(REVIEW_SECONDS)
				.append(" bpm=").append(REVIEW_BPM)
				.append(" genomeSeed=").append(seed).append("\n\n");

		// Active note count per channel for this genome, so a reviewer knows how busy each
		// channel is (a channel with 0 active notes will be silent regardless of its choices).
		int[] elementsByChannel = new int[channelCount];
		for (PatternLayerManager plm
				: scene.getPatternManager().getPatterns()) {
			int channel = plm.getChannel();
			if (channel >= 0 && channel < channelCount) {
				elementsByChannel[channel] += plm.getAllElements(0.0, plm.getDuration()).size();
			}
		}

		sb.append("What to expect (channel -> instruments -> active notes):\n");
		List<NoteAudioChoice> choices = scene.getPatternManager().getChoices();
		for (int ch = 0; ch < channelCount; ch++) {
			sb.append("  channel ").append(ch).append("  [")
					.append(elementsByChannel[ch]).append(" active notes]\n");
			for (NoteAudioChoice choice : choices) {
				if (choice.getChannels() == null || !choice.getChannels().contains(ch)) continue;
				sb.append("      \"").append(choice.getName()).append("\"");
				List<String> files = explicitSampleFiles(choice);
				if (!files.isEmpty()) {
					sb.append(" -> ").append(String.join(", ", files));
				} else {
					sb.append(" -> (samples selected from the library tree)");
				}
				sb.append("\n");
			}
		}

		Files.write(manifestFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
		log(sb.toString());
	}

	/**
	 * Returns the file names of any sources on the choice whose origin names a concrete
	 * audio file (as opposed to a library-tree root that resolves dynamically).
	 *
	 * @param choice the note-audio choice
	 * @return concrete sample file names, possibly empty
	 */
	private List<String> explicitSampleFiles(NoteAudioChoice choice) {
		List<String> files = new ArrayList<>();
		if (choice.getSources() == null) return files;
		for (NoteAudioSource source : choice.getSources()) {
			String origin = source.getOrigin();
			if (origin == null) continue;
			String lower = origin.toLowerCase();
			if (lower.endsWith(".wav") || lower.endsWith(".aif") || lower.endsWith(".aiff")) {
				int slash = Math.max(origin.lastIndexOf('/'), origin.lastIndexOf('\\'));
				files.add(slash >= 0 ? origin.substring(slash + 1) : origin);
			}
		}
		return files;
	}

	/**
	 * Asserts that the given samples are all finite and that the peak amplitude exceeds
	 * {@link #SILENCE_THRESHOLD}.
	 *
	 * @param label   path label for assertion messages
	 * @param samples the rendered samples
	 */
	private void assertFiniteNonSilent(String label, double[] samples) {
		Assert.assertTrue(label + " produced no samples", samples.length > 0);

		double peak = 0.0;
		for (double sample : samples) {
			Assert.assertTrue(label + " produced a non-finite sample", Double.isFinite(sample));
			peak = Math.max(peak, Math.abs(sample));
		}

		log(label + " path: samples=" + samples.length + " peak=" + peak);
		Assert.assertTrue(label + " output is silent (peak=" + peak + ")",
				peak > SILENCE_THRESHOLD);
	}
}
