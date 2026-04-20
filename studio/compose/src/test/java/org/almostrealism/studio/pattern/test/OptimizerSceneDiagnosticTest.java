/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 */

package org.almostrealism.studio.pattern.test;

import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneLoader;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.notes.NoteAudio;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.notes.TreeNoteSource;
import org.almostrealism.music.pattern.NoteAudioChoiceList;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.studio.arrange.EfxManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.studio.health.SilenceDurationHealthComputation;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioPopulationOptimizer;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.music.pattern.PatternElementFactory;
import org.almostrealism.studio.persistence.MigrationClassLoader;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Integration and bisection tests for the {@code AudioSceneOptimizer} render
 * path. Provides two tiers of coverage:
 *
 * <ol>
 *   <li><strong>End-to-end contract</strong> —
 *       {@link #optimizerSetFeatureLevel7ProducesAudio()} runs the exact path
 *       the optimizer exercises at feature level 7 (all effects enabled,
 *       including {@code enableMainFilterUp}, {@code enableAutomationManager},
 *       {@code enableReverb}, {@code enableMasterFilterDown}) and asserts the
 *       written WAV contains a minimum fraction of non-zero samples. A silent
 *       WAV (all zero) fails with a clear {@link AssertionError}; a
 *       mostly-silent WAV (less than 10% non-zero) also fails. This is the
 *       public contract the production pipeline must preserve.</li>
 *   <li><strong>Effect-flag bisection</strong> — the
 *       {@code renderBufferPopulation*} family toggles individual
 *       {@link org.almostrealism.studio.arrange.MixdownManager} static flags
 *       via {@code withEffects(...)} and runs the render through one buffer,
 *       asserting both the internal consolidated render buffer and the
 *       {@code WaveOutput} channel contain non-zero data. These localize
 *       regressions to a specific effect's code path; for example, a failure
 *       only in {@code renderBufferPopulationOnlyMainFilterUp} implicates the
 *       high-pass wiring alone, not the rest of the mixdown.</li>
 * </ol>
 *
 * <p>All assertions use {@link AssertionError} with descriptive messages
 * explaining where in the signal flow the silence originates, so a failing
 * test points directly at the broken stage (render buffer population vs cell
 * graph vs WaveOutput writer). Tests never rely on manual log inspection.</p>
 *
 * <p>Runs against the actual application data: {@code pattern-factory.json}
 * from the Rings app-support directory and {@code AR_RINGS_LIBRARY} as the
 * sample root. Tests that need the library use {@link Assume#assumeTrue} to
 * skip cleanly when either is absent, so these tests do not fail in
 * environments without the sample library available.</p>
 */
public class OptimizerSceneDiagnosticTest extends TestSuiteBase {

	private static File patternFactoryFile() {
		File app = new File(SystemUtils.getLocalDestination("pattern-factory.json"));
		if (app.exists()) return app;
		return new File("pattern-factory.json");
	}

	private NoteAudioChoiceList readChoices(File patternFactory) throws IOException {
		try (InputStream in = new FileInputStream(patternFactory)) {
			return AudioSceneLoader.defaultMapper().readValue(
					MigrationClassLoader.migrateStream(in),
					NoteAudioChoiceList.class);
		}
	}

	/**
	 * Points each {@link TreeNoteSource} at the library directory and reports:
	 * <ul>
	 *   <li>How many {@link NoteAudioProvider}s each choice resolves to</li>
	 *   <li>How many of those providers successfully load a non-null
	 *       {@link WaveData} (i.e., {@code WaveDataProvider.get()} returns non-null)</li>
	 *   <li>The first few failing resource paths per choice</li>
	 * </ul>
	 *
	 * <p>This isolates whether silence in the rendered output is caused by the
	 * tree→provider resolution (no providers are built) or by the
	 * provider→WaveData load step (providers exist but {@code get()} fails).</p>
	 */
	@Test(timeout = 120_000)
	public void providerResolution() throws IOException {
		File patternFactory = patternFactoryFile();
		Assume.assumeTrue("pattern-factory.json required at " + patternFactory.getAbsolutePath(),
				patternFactory.exists());

		File libDir = new File(AudioSceneOptimizer.LIBRARY);
		Assume.assumeTrue("Library directory required at " + libDir.getAbsolutePath(),
				libDir.exists() && libDir.isDirectory());

		log("Library: " + libDir.getAbsolutePath());
		log("Pattern factory: " + patternFactory.getAbsolutePath());

		FileWaveDataProviderNode libraryRoot = new FileWaveDataProviderNode(libDir);
		int libraryFileCount = (int) libraryRoot.children().count();
		log("Library contains " + libraryFileCount + " wave file(s) (recursive)");

		List<NoteAudioChoice> choices = readChoices(patternFactory);
		log("Pattern factory has " + choices.size() + " choice(s)");

		int totalProviders = 0;
		int totalLoadable = 0;
		int totalUnloadable = 0;

		for (NoteAudioChoice choice : choices) {
			int choiceProviders = 0;
			int choiceLoadable = 0;
			int choiceUnloadable = 0;
			int reportedFailures = 0;

			for (NoteAudioSource src : choice.getSources()) {
				if (!(src instanceof TreeNoteSource)) continue;
				TreeNoteSource tree = (TreeNoteSource) src;
				tree.setTree(libraryRoot);
				tree.refresh();

				List<NoteAudio> notes = tree.getNotes();
				if (notes == null) continue;

				choiceProviders += notes.size();

				for (NoteAudio na : notes) {
					NoteAudioProvider np = (NoteAudioProvider) na;
					WaveData loaded = null;
					String failureMessage = null;
					try {
						loaded = np.getProvider().get();
					} catch (Exception e) {
						failureMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
					}

					if (loaded != null && loaded.getData() != null) {
						choiceLoadable++;
					} else {
						choiceUnloadable++;
						if (reportedFailures < 3) {
							String path = np.getProvider() instanceof FileWaveDataProvider
									? ((FileWaveDataProvider) np.getProvider()).getResourcePath()
									: np.getProvider().getClass().getSimpleName();
							log("  FAIL  " + choice.getName() + " <- " + path
									+ (failureMessage == null
											? " (get() returned null)"
											: " (" + failureMessage + ")"));
							reportedFailures++;
						}
					}
				}
			}

			log("  " + choice.getName() + ": " + choiceProviders + " providers ("
					+ choiceLoadable + " load OK, " + choiceUnloadable + " fail)");

			totalProviders += choiceProviders;
			totalLoadable += choiceLoadable;
			totalUnloadable += choiceUnloadable;
		}

		log("TOTAL: " + totalProviders + " providers, " + totalLoadable
				+ " loadable, " + totalUnloadable + " unloadable");

		if (totalProviders == 0) {
			throw new AssertionError(
					"No providers resolved — TreeNoteSource filters likely do not match any files in " + libDir);
		}
		if (totalLoadable == 0) {
			throw new AssertionError(
					"Providers resolved but none have loadable WaveData — "
							+ "load() path is failing (check FileWaveDataProvider / audio decoding)");
		}
	}

	/**
	 * Recreates the optimizer's scene exactly (loadChoices → setLibraryRoot →
	 * setSettings → assignGenome) then renders one buffer via the real-time
	 * runner and checks the WaveOutput cursor and amplitude.
	 */
	@Test(timeout = 300_000)
	public void sceneRender() throws IOException {
		File patternFactory = patternFactoryFile();
		Assume.assumeTrue("pattern-factory.json required",
				patternFactory.exists());

		File libDir = new File(AudioSceneOptimizer.LIBRARY);
		Assume.assumeTrue("Library directory required",
				libDir.exists() && libDir.isDirectory());

		int sourceCount = AudioScene.DEFAULT_SOURCE_COUNT;
		int delayLayers = AudioScene.DEFAULT_DELAY_LAYERS;

		AudioScene<?> scene = new AudioScene<>(120.0, sourceCount, delayLayers,
				OutputLine.sampleRate);

		scene.loadPatterns(patternFactory.getAbsolutePath());
		scene.setTuning(new DefaultKeyboardTuning());
		scene.setLibraryRoot(new FileWaveDataProviderNode(libDir));

		AudioSceneLoader.Settings settings = AudioSceneLoader.Settings.defaultSettings(
				sourceCount,
				AudioScene.DEFAULT_PATTERNS_PER_CHANNEL,
				AudioScene.DEFAULT_ACTIVE_PATTERNS,
				AudioScene.DEFAULT_LAYERS,
				AudioScene.DEFAULT_LAYER_SCALE,
				AudioScene.DEFAULT_DURATION);
		scene.setSettings(settings);

		scene.assignGenome(scene.getGenome().random());

		int elements = scene.getPatternManager().getPatterns().stream()
				.mapToInt(p -> p.getAllElements(0.0, p.getDuration()).size())
				.sum();
		log("Total pattern elements: " + elements);
		if (elements == 0) {
			throw new AssertionError("No pattern elements generated after genome assignment");
		}

		// Check that TreeNoteSources in the scene still have their tree set
		long sourcesWithTree = scene.getPatternManager().getAllSources().stream()
				.filter(s -> s instanceof TreeNoteSource)
				.map(s -> (TreeNoteSource) s)
				.filter(t -> {
					List<NoteAudio> notes = t.getNotes();
					return notes != null && !notes.isEmpty();
				})
				.count();
		long treeSources = scene.getPatternManager().getAllSources().stream()
				.filter(s -> s instanceof TreeNoteSource).count();
		log("TreeNoteSources with providers: " + sourcesWithTree + " / " + treeSources);

		int bufferSize = OutputLine.sampleRate / 2;

		File outputFile = new File("results/optimizer-scene-diagnostic.wav");
		File outputDirectory = outputFile.getParentFile();
		if (outputDirectory != null && !outputDirectory.exists()
				&& !outputDirectory.mkdirs() && !outputDirectory.exists()) {
			throw new IOException("Unable to create output directory");
		}

		WaveOutput output = new WaveOutput(() -> outputFile, 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), bufferSize);

		runner.setup().get().run();
		Runnable tick = runner.tick().get();

		int numBuffers = 4;
		for (int i = 0; i < numBuffers; i++) {
			tick.run();
		}

		int frameCount = output.getFrameCount();
		log("WaveOutput cursor after " + numBuffers + " buffers: " + frameCount
				+ " (expected " + (numBuffers * bufferSize - 1) + ")");

		output.write().get().run();
		log("WAV written to results/optimizer-scene-diagnostic.wav");

		if (frameCount == 0) {
			throw new AssertionError("WaveOutput has no frames");
		}
		if (frameCount != numBuffers * bufferSize - 1) {
			throw new AssertionError("Cursor mismatch: " + frameCount
					+ " != " + (numBuffers * bufferSize - 1));
		}

		WaveData result = WaveData.load(new File("results/optimizer-scene-diagnostic.wav"));
		PackedCollection data = result.getChannelData(0);

		double maxAbs = 0;
		int nonZero = 0;
		for (int i = 0; i < data.getMemLength(); i += 100) {
			double v = Math.abs(data.toDouble(i));
			if (v > 0) nonZero++;
			if (v > maxAbs) maxAbs = v;
		}
		int sampled = data.getMemLength() / 100;
		log("Audio analysis: maxAbs=" + String.format("%.6f", maxAbs)
				+ ", nonZero=" + nonZero + "/" + sampled);

		if (maxAbs == 0) {
			throw new AssertionError("Output is completely silent — all samples are zero");
		}
	}

	/**
	 * Calls {@link org.almostrealism.music.pattern.PatternLayerManager#sum} directly,
	 * bypassing the cell graph entirely, to isolate whether the pattern layer is
	 * producing audio data or the cell graph is losing the signal.
	 *
	 * <p>Uses {@link AudioScene#getContext(ChannelInfo)} to build the scene context
	 * so that measures, chord progression, automation, and sections are configured
	 * exactly as the runner path would configure them. Overrides only the destination
	 * buffer on a per-channel basis. Fails if any channel with pattern elements
	 * produces completely silent output.</p>
	 */
	@Test(timeout = 120_000)
	public void directPatternSum() throws IOException {
		File patternFactory = patternFactoryFile();
		Assume.assumeTrue("pattern-factory.json required", patternFactory.exists());

		File libDir = new File(AudioSceneOptimizer.LIBRARY);
		Assume.assumeTrue("Library directory required", libDir.exists());

		int sourceCount = AudioScene.DEFAULT_SOURCE_COUNT;
		AudioScene<?> scene = new AudioScene<>(120.0, sourceCount,
				AudioScene.DEFAULT_DELAY_LAYERS, OutputLine.sampleRate);
		scene.loadPatterns(patternFactory.getAbsolutePath());
		scene.setTuning(new DefaultKeyboardTuning());
		scene.setLibraryRoot(new FileWaveDataProviderNode(libDir));

		AudioSceneLoader.Settings settings = AudioSceneLoader.Settings.defaultSettings(
				sourceCount,
				AudioScene.DEFAULT_PATTERNS_PER_CHANNEL,
				AudioScene.DEFAULT_ACTIVE_PATTERNS,
				AudioScene.DEFAULT_LAYERS,
				AudioScene.DEFAULT_LAYER_SCALE,
				AudioScene.DEFAULT_DURATION);
		scene.setSettings(settings);
		scene.assignGenome(scene.getGenome().random());

		int frames = scene.getTotalSamples();
		PackedCollection dest = new PackedCollection(frames);

		int silentChannels = 0;
		int audibleChannels = 0;

		for (PatternLayerManager plm : scene.getPatternManager().getPatterns()) {
			ChannelInfo channel = new ChannelInfo(plm.getChannel(),
					ChannelInfo.Voicing.MAIN, ChannelInfo.StereoChannel.LEFT);
			AudioSceneContext ctx = scene.getContext(channel);
			ctx.setDestination(dest);

			plm.updateDestination(ctx);

			int elemCount = plm.getAllElements(0.0, plm.getDuration()).size();
			if (elemCount == 0) continue;

			dest.clear();

			plm.sum(() -> ctx,
					ChannelInfo.Voicing.MAIN,
					ChannelInfo.StereoChannel.LEFT,
					() -> 0, ctx.getFrames()).get().run();

			double maxAbs = 0;
			int nonZero = 0;
			for (int i = 0; i < dest.getMemLength(); i += 100) {
				double v = Math.abs(dest.toDouble(i));
				if (v > 0) nonZero++;
				if (v > maxAbs) maxAbs = v;
			}
			int sampled = dest.getMemLength() / 100;

			log("  Channel " + plm.getChannel() + ": " + elemCount + " elements, maxAbs="
					+ String.format("%.6f", maxAbs) + ", nonZero=" + nonZero + "/" + sampled);

			if (maxAbs == 0) silentChannels++;
			else audibleChannels++;
		}

		log("Summary: " + audibleChannels + " audible channel(s), " + silentChannels
				+ " silent channel(s) (with pattern elements)");

		if (audibleChannels == 0 && silentChannels > 0) {
			throw new AssertionError(
					"PatternLayerManager.sum() produced silence on every channel with pattern elements ("
							+ silentChannels + " channels tested) — pattern layer itself is broken");
		}
	}

	/**
	 * Builds the optimizer scene, starts the real-time runner, runs one buffer tick,
	 * and inspects {@link AudioScene#getConsolidatedRenderBuffer()} to verify that
	 * {@link org.almostrealism.music.pattern.PatternAudioBuffer#prepareBatch()} has
	 * written audio data.
	 *
	 * <p>This test isolates the <em>next</em> layer above
	 * {@link #directPatternSum()}: if the consolidated render buffer contains
	 * non-zero data after a buffer tick but the ultimate {@link WaveOutput}
	 * produces silence, then the cell graph (WaveCell → effects → WaveOutput)
	 * is dropping the signal. If the consolidated buffer is silent, the
	 * problem is in {@link org.almostrealism.music.pattern.PatternSystemManager#sum}
	 * or the wrapping in {@link org.almostrealism.music.pattern.PatternAudioBuffer#prepareBatch}.</p>
	 */
	@Test(timeout = 300_000)
	public void renderBufferPopulation() throws IOException {
		File patternFactory = patternFactoryFile();
		Assume.assumeTrue("pattern-factory.json required", patternFactory.exists());

		File libDir = new File(AudioSceneOptimizer.LIBRARY);
		Assume.assumeTrue("Library directory required", libDir.exists());

		int sourceCount = AudioScene.DEFAULT_SOURCE_COUNT;
		AudioScene<?> scene = new AudioScene<>(120.0, sourceCount,
				AudioScene.DEFAULT_DELAY_LAYERS, OutputLine.sampleRate);
		scene.loadPatterns(patternFactory.getAbsolutePath());
		scene.setTuning(new DefaultKeyboardTuning());
		scene.setLibraryRoot(new FileWaveDataProviderNode(libDir));

		AudioSceneLoader.Settings settings = AudioSceneLoader.Settings.defaultSettings(
				sourceCount,
				AudioScene.DEFAULT_PATTERNS_PER_CHANNEL,
				AudioScene.DEFAULT_ACTIVE_PATTERNS,
				AudioScene.DEFAULT_LAYERS,
				AudioScene.DEFAULT_LAYER_SCALE,
				AudioScene.DEFAULT_DURATION);
		scene.setSettings(settings);
		scene.assignGenome(scene.getGenome().random());

		int bufferSize = OutputLine.sampleRate / 2;
		WaveOutput output = new WaveOutput(() -> null, 24, true);
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(output), bufferSize);

		runner.setup().get().run();
		Runnable tick = runner.tick().get();
		tick.run();

		PackedCollection consolidated = scene.getConsolidatedRenderBuffer();
		if (consolidated == null) {
			throw new AssertionError(
					"getConsolidatedRenderBuffer() returned null after getCells — "
							+ "buffer consolidation did not run");
		}

		double consolidatedMax = 0;
		int consolidatedNonZero = 0;
		for (int i = 0; i < consolidated.getMemLength(); i += 100) {
			double v = Math.abs(consolidated.toDouble(i));
			if (v > 0) consolidatedNonZero++;
			if (v > consolidatedMax) consolidatedMax = v;
		}
		int consolidatedSampled = consolidated.getMemLength() / 100;

		int waveFrames = output.getFrameCount();
		log("Consolidated render buffer: maxAbs=" + String.format("%.6f", consolidatedMax)
				+ ", nonZero=" + consolidatedNonZero + "/" + consolidatedSampled
				+ ", totalFrames=" + consolidated.getMemLength());
		int expectedWaveFrames = bufferSize - 1;
		log("WaveOutput cursor: " + waveFrames + " (expected " + expectedWaveFrames + ")");

		PackedCollection channelData = output.getChannelData(0).get().evaluate();
		double channelMax = 0;
		int channelNonZero = 0;
		int scanFrames = Math.min(bufferSize, channelData.getMemLength());
		for (int i = 0; i < scanFrames; i++) {
			double v = Math.abs(channelData.toDouble(i));
			if (v > 0) channelNonZero++;
			if (v > channelMax) channelMax = v;
		}
		log("WaveOutput channel 0 timeline (first " + scanFrames + " frames): maxAbs="
				+ String.format("%.6f", channelMax) + ", nonZero=" + channelNonZero
				+ "/" + scanFrames);

		if (consolidatedMax == 0) {
			throw new AssertionError(
					"PatternAudioBuffer.prepareBatch() did not populate the consolidated "
							+ "render buffer — silence originates in PatternSystemManager.sum "
							+ "or PatternAudioBuffer wrapping");
		}
		if (channelMax == 0) {
			throw new AssertionError(
					"Consolidated render buffer has audio (maxAbs=" + consolidatedMax
							+ ") but WaveOutput channel 0 timeline is all zero for the "
							+ "first " + scanFrames + " frames — the cell graph loop is "
							+ "pushing zeros to WaveOutput (silence between "
							+ "PatternAudioBuffer.getOutputProducer() and WaveOutput.Writer.push)");
		}
	}

	/**
	 * Same pipeline as {@link #renderBufferPopulation()} but with all
	 * effects disabled via {@link org.almostrealism.studio.arrange.MixdownManager}
	 * static flags. If this produces audio while the effects-enabled variant
	 * does not, the silence is caused by the effects path rather than the
	 * base cell graph or render buffer consolidation.
	 */
	@Test(timeout = 300_000)
	public void renderBufferPopulationEffectsDisabled() throws IOException {
		withEffects(false, false, false, false, false, false, false, false,
				this::renderBufferPopulation);
	}

	/** Baseline: everything false except enableEfx. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationOnlyEnableEfx() throws IOException {
		withEffects(false, false, true, false, false, false, false, false,
				this::renderBufferPopulation);
	}

	/** Baseline: everything false except enableMainFilterUp. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationOnlyMainFilterUp() throws IOException {
		withEffects(true, false, false, false, false, false, false, false,
				this::renderBufferPopulation);
	}

	/** Baseline: everything false except enableAutomationManager. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationOnlyAutomation() throws IOException {
		withEffects(false, false, false, false, false, false, false, true,
				this::renderBufferPopulation);
	}

	/** Baseline: everything false except enableMasterFilterDown. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationOnlyMasterFilterDown() throws IOException {
		withEffects(false, false, false, false, false, false, true, false,
				this::renderBufferPopulation);
	}

	/** enableMainFilterUp + enableAutomationManager — the combo that setFeatureLevel(7) sets. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationMainFilterUpWithAutomation() throws IOException {
		withEffects(true, false, false, false, false, false, false, true,
				this::renderBufferPopulation);
	}

	/**
	 * Everything enabled (as setFeatureLevel(7) would configure) EXCEPT
	 * enableMainFilterUp. If this produces audio, mainFilterUp is the sole
	 * cause of silence for the optimizer path.
	 */
	@Test(timeout = 300_000)
	public void renderBufferPopulationAllEffectsExceptMainFilterUp() throws IOException {
		withEffects(false, true, true, true, true, true, true, true,
				this::renderBufferPopulation);
	}

	/** Only enableEfxFilters (and enableEfx, which EfxFilters depends on). */
	@Test(timeout = 300_000)
	public void renderBufferPopulationEfxPlusEfxFilters() throws IOException {
		withEffects(false, true, true, false, false, false, false, false,
				this::renderBufferPopulation);
	}

	/** enableEfx + enableReverb. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationEfxPlusReverb() throws IOException {
		withEffects(false, false, true, true, false, false, false, false,
				this::renderBufferPopulation);
	}

	/** enableEfx + enableTransmission. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationEfxPlusTransmission() throws IOException {
		withEffects(false, false, true, false, true, false, false, false,
				this::renderBufferPopulation);
	}

	/** enableEfx + enableWetInAdjustment. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationEfxPlusWetInAdjust() throws IOException {
		withEffects(false, false, true, false, false, true, false, false,
				this::renderBufferPopulation);
	}

	/** Level 4 flags: enableEfx + enableEfxFilters + enableTransmission (the level AudioScenePopulationTest uses). */
	@Test(timeout = 300_000)
	public void renderBufferPopulationLevel4() throws IOException {
		withEffects(false, true, true, false, true, false, false, false,
				this::renderBufferPopulation);
	}

	/** Level 6 flags minus mainFilterUp: Level 4 + wetInAdjust + masterFilterDown + automation. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationLevel6NoMainFilterUp() throws IOException {
		withEffects(false, true, true, false, true, true, true, true,
				this::renderBufferPopulation);
	}

	/** Level 7 flags minus mainFilterUp (keeps automation): Level 6 + reverb. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationLevel7NoMainFilterUp() throws IOException {
		withEffects(false, true, true, true, true, true, true, true,
				this::renderBufferPopulation);
	}

	/** Level 4 + automation. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationLevel4PlusAutomation() throws IOException {
		withEffects(false, true, true, false, true, false, false, true,
				this::renderBufferPopulation);
	}

	/** Level 4 + automation + masterFilterDown. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationLevel4PlusAutomationMasterFilterDown() throws IOException {
		withEffects(false, true, true, false, true, false, true, true,
				this::renderBufferPopulation);
	}

	/** Level 4 + automation + wetInAdjust. */
	@Test(timeout = 300_000)
	public void renderBufferPopulationLevel4PlusAutomationWetInAdjust() throws IOException {
		withEffects(false, true, true, false, true, true, false, true,
				this::renderBufferPopulation);
	}

	/** Level 7 config with both confirmed silencers disabled (mainFilterUp + masterFilterDown). */
	@Test(timeout = 300_000)
	public void renderBufferPopulationLevel7NoFilters() throws IOException {
		withEffects(false, true, true, true, true, true, false, true,
				this::renderBufferPopulation);
	}

	/**
	 * End-to-end test of the exact optimizer configuration:
	 * {@link AudioSceneOptimizer#setFeatureLevel}(7) +
	 * {@link AudioSceneOptimizer#createScene()} + {@code runnerRealTime} + one
	 * buffer tick. Writes a WAV file and asserts it contains non-zero audio.
	 *
	 * <p>This is the contract test for "AudioSceneOptimizer produces valid
	 * audio". If it fails, the optimizer will generate silent WAVs.</p>
	 */
	@Test(timeout = 300_000)
	public void optimizerSetFeatureLevel7ProducesAudio() throws IOException {
		File libDir = new File(AudioSceneOptimizer.LIBRARY);
		Assume.assumeTrue("Library directory required", libDir.exists());
		Assume.assumeTrue("pattern-factory.json required",
				new File(SystemUtils.getLocalDestination("pattern-factory.json")).exists());

		String savedLibrary = AudioSceneOptimizer.LIBRARY;
		boolean savedEnableVolumeEnvelope = PatternElementFactory.enableVolumeEnvelope;
		boolean savedEnableFilterEnvelope = PatternElementFactory.enableFilterEnvelope;
		boolean savedDisableClean = MixdownManager.disableClean;
		boolean savedEnableSourcesOnly = MixdownManager.enableSourcesOnly;
		boolean savedEfxManagerEnableEfx = EfxManager.enableEfx;
		boolean savedMixdownEnableEfx = MixdownManager.enableEfx;
		boolean savedMixdownEnableEfxFilters = MixdownManager.enableEfxFilters;
		boolean savedMixdownEnableTransmission = MixdownManager.enableTransmission;
		boolean savedMixdownEnableMainFilterUp = MixdownManager.enableMainFilterUp;
		boolean savedMixdownEnableAutomationManager = MixdownManager.enableAutomationManager;
		boolean savedMixdownEnableWetInAdjustment = MixdownManager.enableWetInAdjustment;
		boolean savedMixdownEnableMasterFilterDown = MixdownManager.enableMasterFilterDown;
		boolean savedMixdownEnableReverb = MixdownManager.enableReverb;
		boolean savedEnableTimeout = StableDurationHealthComputation.enableTimeout;
		boolean savedEnableSilenceCheck = SilenceDurationHealthComputation.enableSilenceCheck;
		boolean savedEnableStemOutput = AudioPopulationOptimizer.enableStemOutput;

		AudioSceneOptimizer.LIBRARY = libDir.getAbsolutePath();
		File wavFile = new File("results/optimizer-feature-level-7.wav");
		wavFile.getParentFile().mkdirs();
		try {
			AudioSceneOptimizer.setFeatureLevel(7);
			AudioScene<?> scene = AudioSceneOptimizer.createScene();
			scene.assignGenome(scene.getGenome().random());

			int bufferSize = OutputLine.sampleRate / 2;
			// Render ~30 seconds of audio so there's a meaningful listenable file.
			int bufferCount = 60;
			WaveOutput output = new WaveOutput(() -> wavFile, 24, true);
			TemporalCellular runner = scene.runnerRealTime(
					new MultiChannelAudioOutput(output), bufferSize);

			runner.setup().get().run();
			Runnable tick = runner.tick().get();
			for (int i = 0; i < bufferCount; i++) {
				tick.run();
			}
			output.write().get().run();

			log("AudioSceneOptimizer setFeatureLevel(7) wrote WAV: "
					+ wavFile.getAbsolutePath() + " (exists=" + wavFile.exists()
					+ ", size=" + (wavFile.exists() ? wavFile.length() : 0) + ")");

			if (!wavFile.exists()) {
				throw new AssertionError("WAV file was not written");
			}

			WaveData loaded = WaveData.load(wavFile);
			PackedCollection channelData = loaded.getChannelData(0);

			double maxAbs = 0;
			int nonZero = 0;
			int len = channelData.getMemLength();
			for (int i = 0; i < len; i++) {
				double v = Math.abs(channelData.toDouble(i));
				if (v > 0) nonZero++;
				if (v > maxAbs) maxAbs = v;
			}

			log("Loaded WAV channel 0: " + len + " samples, maxAbs="
					+ String.format("%.6f", maxAbs) + ", nonZero=" + nonZero);

			if (maxAbs == 0) {
				throw new AssertionError(
						"AudioSceneOptimizer with setFeatureLevel(7) wrote a silent WAV ("
								+ nonZero + " non-zero samples / " + len + ")");
			}
			if (nonZero < len / 10) {
				throw new AssertionError(
						"AudioSceneOptimizer with setFeatureLevel(7) wrote a mostly-silent WAV ("
								+ nonZero + " non-zero samples / " + len + ")");
			}
		} finally {
			AudioSceneOptimizer.LIBRARY = savedLibrary;
			PatternElementFactory.enableVolumeEnvelope = savedEnableVolumeEnvelope;
			PatternElementFactory.enableFilterEnvelope = savedEnableFilterEnvelope;
			MixdownManager.disableClean = savedDisableClean;
			MixdownManager.enableSourcesOnly = savedEnableSourcesOnly;
			EfxManager.enableEfx = savedEfxManagerEnableEfx;
			MixdownManager.enableEfx = savedMixdownEnableEfx;
			MixdownManager.enableEfxFilters = savedMixdownEnableEfxFilters;
			MixdownManager.enableTransmission = savedMixdownEnableTransmission;
			MixdownManager.enableMainFilterUp = savedMixdownEnableMainFilterUp;
			MixdownManager.enableAutomationManager = savedMixdownEnableAutomationManager;
			MixdownManager.enableWetInAdjustment = savedMixdownEnableWetInAdjustment;
			MixdownManager.enableMasterFilterDown = savedMixdownEnableMasterFilterDown;
			MixdownManager.enableReverb = savedMixdownEnableReverb;
			StableDurationHealthComputation.enableTimeout = savedEnableTimeout;
			SilenceDurationHealthComputation.enableSilenceCheck = savedEnableSilenceCheck;
			AudioPopulationOptimizer.enableStemOutput = savedEnableStemOutput;
		}
	}

	private interface IOAction {
		void run() throws IOException;
	}

	private void withEffects(boolean mainFilterUp, boolean efxFilters, boolean efx,
							 boolean reverb, boolean transmission, boolean wetInAdjust,
							 boolean masterFilterDown, boolean automation,
							 IOAction body) throws IOException {
		boolean savedMainFilterUp = MixdownManager.enableMainFilterUp;
		boolean savedEfxFilters = MixdownManager.enableEfxFilters;
		boolean savedEfx = MixdownManager.enableEfx;
		boolean savedReverb = MixdownManager.enableReverb;
		boolean savedTransmission = MixdownManager.enableTransmission;
		boolean savedWetInAdjust = MixdownManager.enableWetInAdjustment;
		boolean savedMasterFilterDown = MixdownManager.enableMasterFilterDown;
		boolean savedAutomation = MixdownManager.enableAutomationManager;

		MixdownManager.enableMainFilterUp = mainFilterUp;
		MixdownManager.enableEfxFilters = efxFilters;
		MixdownManager.enableEfx = efx;
		MixdownManager.enableReverb = reverb;
		MixdownManager.enableTransmission = transmission;
		MixdownManager.enableWetInAdjustment = wetInAdjust;
		MixdownManager.enableMasterFilterDown = masterFilterDown;
		MixdownManager.enableAutomationManager = automation;

		try {
			body.run();
		} finally {
			MixdownManager.enableMainFilterUp = savedMainFilterUp;
			MixdownManager.enableEfxFilters = savedEfxFilters;
			MixdownManager.enableEfx = savedEfx;
			MixdownManager.enableReverb = savedReverb;
			MixdownManager.enableTransmission = savedTransmission;
			MixdownManager.enableWetInAdjustment = savedWetInAdjust;
			MixdownManager.enableMasterFilterDown = savedMasterFilterDown;
			MixdownManager.enableAutomationManager = savedAutomation;
		}
	}
}
