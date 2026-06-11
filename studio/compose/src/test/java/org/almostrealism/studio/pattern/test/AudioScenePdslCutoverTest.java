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

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.SystemUtils;
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

	/** Curated pattern factory; the real arrangement that decides which samples play where. */
	private static final String PATTERN_FACTORY =
			SystemUtils.getProperty("AR_RINGS_PATTERNS", "/Users/Shared/Music/pattern-factory.json");

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
		// The PDSL path always renders its own reverb send arm (independent of this flag); the
		// Java reverb bus is left off here so the CellList baseline compiles/renders quickly
		// enough to finish within the test-runner window. The PDSL reverb is heard regardless.
		MixdownManager.enableReverb = false;
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
	 * Loads a fresh scene from the real curated arrangement (recursive library + pattern
	 * factory), mirroring {@link org.almostrealism.studio.optimize.AudioSceneOptimizer#createScene()}
	 * but with explicit paths so it does not depend on the working directory.
	 *
	 * @param library        the recursive sample library root
	 * @param patternFactory the curated pattern factory JSON
	 * @return a freshly loaded scene
	 * @throws IOException if the scene cannot be loaded
	 */
	private AudioScene<?> loadRealScene(File library, File patternFactory) throws IOException {
		AudioScene<?> scene = AudioScene.load(null, patternFactory.getAbsolutePath(),
				library.getAbsolutePath(), REVIEW_BPM, SAMPLE_RATE);
		scene.setTotalMeasures(REVIEW_MEASURES);
		return scene;
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
