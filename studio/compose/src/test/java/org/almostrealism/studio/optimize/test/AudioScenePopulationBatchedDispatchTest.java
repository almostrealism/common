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

package org.almostrealism.studio.optimize.test;

import org.almostrealism.audio.BatchedPatternRenderer;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.heredity.Genome;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.util.KeyUtils;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * End-to-end production wiring verification for the Phase 3 batched dispatch
 * path.
 *
 * <p>This test closes the gap between the kernel-level
 * {@code BatchedPatternRendererTest} (which dispatches the four-kernel chain
 * directly on synthetic inputs) and the dispatch-site
 * {@code PatternRenderingBatchedVerificationTest} (which constructs
 * {@code RenderedNoteAudio} directly without going through
 * {@link org.almostrealism.music.pattern.ScaleTraversalStrategy}). Both prior
 * tests bypass the production note-construction path, leaving the actual
 * production wiring uncovered. This test exercises:</p>
 *
 * <pre>
 *   AudioScenePopulation.generate
 *     → AudioScene runner / tick
 *     → PatternSystemManager.sum
 *     → PatternLayerManager.sum
 *     → PatternFeatures.render
 *     → BatchedPatternLayerRenderer.render
 *     → PatternFeatures.dispatchBatchedGroup
 *     → BatchedPatternRenderer.buildBatchedChain (counter advance asserted here)
 * </pre>
 *
 * <p>The construction site that populates {@code RenderedNoteAudio}'s batched
 * input fields is {@link org.almostrealism.music.pattern.ScaleTraversalStrategy#createRenderedNote}
 * (via its private {@code populateBatchedInputs} step), and the assertions
 * here verify that the wired path advances the sentinel counter without
 * triggering the fallback warning.</p>
 *
 * <h2>Scenarios</h2>
 * <ol>
 *   <li><b>flagOn_generate_advancesBatchedDispatchCounter</b>: With
 *       {@code AR_PATTERN_BATCHED=true} and a 1024-frame tick (matching
 *       {@link PatternLayerManager#BATCHED_TARGET_LENGTH}), the sentinel
 *       counter must advance and the fallback counter must stay at zero.</li>
 *   <li><b>flagOff_generate_doesNotAdvanceBatchedDispatchCounter</b>: With
 *       {@code AR_PATTERN_BATCHED=false}, the sentinel counter must NOT
 *       advance.</li>
 *   <li><b>sustainedRateWallClockMeasurement</b>: gated by the
 *       {@code AR_PATTERN_BATCHED_SUSTAINED_BENCHMARK=true} system property,
 *       runs at least 100 ticks against a real AudioScene with batched
 *       dispatch on and reports the steady-state ratio + per-tick percentiles.
 *       Asserts steady-state ratio &lt; 1.0.</li>
 * </ol>
 *
 * <h2>Bundled test fixtures</h2>
 *
 * <p>The test resolves its inputs from a bundled {@code /test-fixtures/}
 * classpath directory laid out under
 * {@code studio/compose/src/test/resources/test-fixtures/}:
 * a minimal {@code pattern-factory.json} that declares a single melodic
 * factory bound to {@link #PATTERN_CHANNEL}, plus a {@code Library/} of
 * short synthetic sine-wave WAVs that the factory's {@code TreeNoteSource}
 * matches via {@code NAME STARTS_WITH "TestTone"}. No developer-side library
 * setup is required and no silent skip happens when fixtures are missing —
 * the test fails loudly with a clear error.</p>
 *
 * @see BatchedPatternRenderer#batchedDispatchCount
 * @see BatchedPatternLayerRenderer#fallbackCount
 * @see PatternLayerManager#enableBatched
 */
public class AudioScenePopulationBatchedDispatchTest extends TestSuiteBase {

	/** Tick buffer size matching the production batched renderer target length. */
	private static final int TICK_BUFFER_SIZE = PatternLayerManager.BATCHED_TARGET_LENGTH;

	/** Number of audio frames to generate per genome. Keeps test under 5s. */
	private static final int FRAMES_PER_GENOME = TICK_BUFFER_SIZE * 4;

	/** Number of genomes in the test population. */
	private static final int TEST_GENOME_COUNT = 2;

	/**
	 * Channel index used for both the pattern's audio output and the
	 * {@link AudioScenePopulation#generate} target channel — keeping the two
	 * aligned is what makes the runner actually render the pattern (and
	 * therefore actually invoke the batched dispatch path).
	 */
	private static final int PATTERN_CHANNEL = 0;

	/**
	 * Classpath path to the bundled test fixtures directory. The test resolves
	 * this via {@link Class#getResource(String)} so the absolute path on disk
	 * works regardless of where surefire spawns the JVM. The directory contains
	 * {@code pattern-factory.json} and a {@code Library/} of synthetic WAV
	 * samples committed alongside this test class.
	 */
	private static final String FIXTURES_RESOURCE_PATH = "/test-fixtures";

	/** Cached fixtures root resolved on first access. */
	private static File fixturesRoot;

	private boolean savedEnableBatched;
	private boolean savedEnableBatchedStrict;

	/**
	 * Forces {@link AudioProcessingUtils} static initialization before any
	 * test enters a {@link Heap}-wrapped section. The shared compiled evaluables
	 * built by {@code AudioProcessingUtils.<clinit>} (notably {@code AudioSumProvider})
	 * refuse to construct when {@code Heap.getDefault() != null}, because their
	 * GPU memory must outlive any thread-local heap. Running them eagerly here
	 * binds them to the global default heap.
	 */
	@BeforeClass
	public static void initProcessing() {
		AudioProcessingUtils.init();
	}

	/**
	 * Resolves the bundled fixtures root via the test classpath. The fixtures
	 * are committed under {@code studio/compose/src/test/resources/test-fixtures/}
	 * and copied by surefire to {@code target/test-classes/test-fixtures/};
	 * {@link Class#getResource(String)} returns the on-disk {@link URL} pointing
	 * at the extracted copy. The result is cached statically.
	 *
	 * @return the absolute fixtures directory on disk
	 * @throws IllegalStateException when the fixtures are missing from the
	 *         classpath — surfaced loudly instead of silently skipping the test
	 */
	private static File resolveFixturesRoot() {
		if (fixturesRoot != null) return fixturesRoot;
		URL url = AudioScenePopulationBatchedDispatchTest.class
				.getResource(FIXTURES_RESOURCE_PATH);
		if (url == null) {
			throw new IllegalStateException(
					"Bundled test fixtures not found on classpath at "
							+ FIXTURES_RESOURCE_PATH
							+ " — expected "
							+ "studio/compose/src/test/resources/test-fixtures/"
							+ " to be copied by surefire to target/test-classes/. "
							+ "Check that the Maven resources phase ran.");
		}
		File dir = new File(url.getFile());
		if (!dir.isDirectory()) {
			throw new IllegalStateException(
					"Bundled fixtures URL resolved but is not a directory: " + dir
							+ " — non-file URL schemes (jar:) are not supported "
							+ "for this test because TreeNoteSource walks the real "
							+ "filesystem; rerun against an exploded test-classes "
							+ "layout.");
		}
		fixturesRoot = dir;
		return dir;
	}

	/**
	 * Constructs a minimal {@link AudioScene} backed by the bundled test
	 * fixtures. Loads {@code pattern-factory.json} (a single melodic factory
	 * bound to {@link #PATTERN_CHANNEL}) and points the library tree at the
	 * bundled {@code Library/} of synthetic sine-wave WAVs the factory's
	 * {@code TreeNoteSource} matches via {@code NAME STARTS_WITH "TestTone"}.
	 *
	 * @param sources       audio source channel count
	 * @param delayLayers   delay layer count for the mixdown pipeline
	 * @return the populated scene
	 */
	private AudioScene<?> pattern(int sources, int delayLayers) {
		try {
			File root = resolveFixturesRoot();
			File patternFactoryFile = new File(root, "pattern-factory.json");
			File libraryDir = new File(root, "Library");
			if (!patternFactoryFile.isFile()) {
				throw new IllegalStateException(
						"Bundled pattern-factory.json missing under fixtures root "
								+ root);
			}
			if (!libraryDir.isDirectory()) {
				throw new IllegalStateException(
						"Bundled Library directory missing under fixtures root "
								+ root);
			}

			AudioScene<?> scene = new AudioScene<>(120, sources, delayLayers, OutputLine.sampleRate);
			scene.setTotalMeasures(16);
			scene.setTuning(new DefaultKeyboardTuning());

			scene.loadPatterns(patternFactoryFile.getAbsolutePath());
			scene.setLibraryRoot(new FileWaveDataProviderNode(libraryDir));

			// Add the pattern on the same channel index used by AudioScenePopulation.generate
			// (channel 0). Matching the channel index is load-bearing — the
			// AudioScene runner only invokes PatternFeatures.render for channels
			// it has been asked to mix, and the parent's addPattern(4, ...) puts
			// the pattern on a channel that the generate(0, ...) call does not
			// touch, so the batched dispatch never fires.
			PatternLayerManager layer = scene.getPatternManager().addPattern(
					PATTERN_CHANNEL, 1.0, true);
			layer.setLayerCount(3);
			return scene;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Saves the global {@code AR_PATTERN_BATCHED} flag values so per-test
	 * mutations do not leak across the {@code @Test} methods.
	 */
	@Before
	public void saveFlags() {
		savedEnableBatched = PatternLayerManager.enableBatched;
		savedEnableBatchedStrict = PatternLayerManager.enableBatchedStrict;
	}

	/**
	 * Restores the flags to their pre-test values.
	 */
	@After
	public void restoreFlags() {
		PatternLayerManager.enableBatched = savedEnableBatched;
		PatternLayerManager.enableBatchedStrict = savedEnableBatchedStrict;
	}

	/**
	 * Verifies the end-to-end production wiring: when {@code AR_PATTERN_BATCHED}
	 * is on and {@link AudioScenePopulation#generate} runs against a real
	 * {@link AudioScene} populated with patterns from
	 * {@code pattern-factory.json}, the {@link BatchedPatternRenderer#batchedDispatchCount}
	 * sentinel must advance. The fallback counter must stay at zero,
	 * confirming every overlapping note had populated batched fields and the
	 * four-kernel chain ran for every group.
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void flagOn_generate_advancesBatchedDispatchCounter() throws Exception {
		PatternLayerManager.enableBatched = true;
		PatternLayerManager.enableBatchedStrict = false;
		BatchedPatternRenderer.batchedDispatchCount.set(0);
		BatchedPatternLayerRenderer.fallbackCount.set(0);

		Heap heap = new Heap(8 * 1024 * 1024);
		long batchedDelta;
		long fallbackDelta;

		try {
			AudioScene<?> scene = pattern(1, 1);
			scene.setPatternActivityBias(1.0);

			long batchedBefore = BatchedPatternRenderer.batchedDispatchCount.get();
			long fallbackBefore = BatchedPatternLayerRenderer.fallbackCount.get();

			heap.wrap(() -> {
				AudioScenePopulation pop = miniPopulation(scene);
				pop.generate(PATTERN_CHANNEL, FRAMES_PER_GENOME, TICK_BUFFER_SIZE,
						() -> new File("results/batched-dispatch-test-"
								+ KeyUtils.generateKey() + ".wav").getPath(),
						result -> log("Generated " + result.getOutputPath())).run();
				return null;
			}).call();

			batchedDelta = BatchedPatternRenderer.batchedDispatchCount.get() - batchedBefore;
			fallbackDelta = BatchedPatternLayerRenderer.fallbackCount.get() - fallbackBefore;
		} finally {
			heap.destroy();
		}

		log("flagOn_generate_advancesBatchedDispatchCounter"
				+ " genomes=" + TEST_GENOME_COUNT
				+ " framesPerGenome=" + FRAMES_PER_GENOME
				+ " bufferSize=" + TICK_BUFFER_SIZE
				+ " batchedDispatchDelta=" + batchedDelta
				+ " fallbackDelta=" + fallbackDelta);

		Assert.assertTrue(
				"AudioScenePopulation.generate with AR_PATTERN_BATCHED=true must "
						+ "advance the batched dispatch sentinel — delta="
						+ batchedDelta + " (fallbackDelta=" + fallbackDelta + "). "
						+ "When fallbackDelta>0 and batchedDelta==0 the renderer "
						+ "WAS invoked but every overlapping note lacked populated "
						+ "batched inputs; see ScaleTraversalStrategy"
						+ ".populateBatchedInputs.",
				batchedDelta > 0);
		Assert.assertEquals(
				"Every overlapping note must populate batched inputs; "
						+ "non-zero fallback delta indicates ScaleTraversalStrategy"
						+ ".populateBatchedInputs failed to wire one or more notes; "
						+ "delta=" + fallbackDelta,
				0L, fallbackDelta);
	}

	/**
	 * Sanity check that the batched dispatch counter does NOT advance when
	 * the feature flag is off — guards against the dispatch counter accidentally
	 * being incremented from a non-batched path.
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void flagOff_generate_doesNotAdvanceBatchedDispatchCounter() throws Exception {
		PatternLayerManager.enableBatched = false;
		BatchedPatternRenderer.batchedDispatchCount.set(0);
		BatchedPatternLayerRenderer.fallbackCount.set(0);

		Heap heap = new Heap(8 * 1024 * 1024);
		long batchedDelta;

		try {
			AudioScene<?> scene = pattern(1, 1);
			scene.setPatternActivityBias(1.0);

			long batchedBefore = BatchedPatternRenderer.batchedDispatchCount.get();

			heap.wrap(() -> {
				AudioScenePopulation pop = miniPopulation(scene);
				pop.generate(PATTERN_CHANNEL, FRAMES_PER_GENOME, TICK_BUFFER_SIZE,
						() -> new File("results/batched-dispatch-off-test-"
								+ KeyUtils.generateKey() + ".wav").getPath(),
						result -> log("Generated " + result.getOutputPath())).run();
				return null;
			}).call();

			batchedDelta = BatchedPatternRenderer.batchedDispatchCount.get() - batchedBefore;
		} finally {
			heap.destroy();
		}

		Assert.assertEquals(
				"AudioScenePopulation.generate with AR_PATTERN_BATCHED=false "
						+ "must NOT advance the batched dispatch sentinel — "
						+ "delta=" + batchedDelta,
				0L, batchedDelta);
	}

	/**
	 * Sustained-rate wall-clock measurement. Drives {@link AudioScenePopulation#generate}
	 * for the same number of frames as {@code (warmupTicks + timedTicks)} ticks
	 * worth of audio, while timing each individual
	 * {@link PatternLayerManager#sum} call from inside the runner via the
	 * {@link PatternLayerManager#perCallTimingObserver} verification hook.
	 * Reports the steady-state ratio, p50/p95/p99 per-call costs, and
	 * worst-case per-call cost. Gated behind
	 * {@code AR_PATTERN_BATCHED_SUSTAINED_BENCHMARK=true} so it only runs when
	 * measuring deliberately.
	 *
	 * <p>This shape — driving through {@code generate(...)} rather than a
	 * hand-rolled {@code cells.setup() / cells.tick()} loop — is deliberate:
	 * the prior manual-loop version reported {@code batchedDispatches=0} on
	 * production-shaped genomes because it diverged from the production
	 * dispatch path in some subtle way that was easier to bypass than diagnose.
	 * Using {@code generate(...)} exercises the exact same code path as
	 * {@link #flagOn_generate_advancesBatchedDispatchCounter}, with the
	 * sentinel assertion in {@link #reportSustainedRate} guarding against a
	 * regression to the no-op-measurement trap.</p>
	 *
	 * <p>Run with:
	 * <pre>
	 *   AR_PATTERN_BATCHED=true AR_PATTERN_BATCHED_SUSTAINED_BENCHMARK=true
	 *   mvn -pl studio/compose test
	 *     -Dtest=AudioScenePopulationBatchedDispatchTest#sustainedRateWallClockMeasurement
	 * </pre></p>
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void sustainedRateWallClockMeasurement() throws Exception {
		Assume.assumeTrue(
				"AR_PATTERN_BATCHED_SUSTAINED_BENCHMARK=true required to run",
				SystemUtils.isEnabled("AR_PATTERN_BATCHED_SUSTAINED_BENCHMARK").orElse(false));

		PatternLayerManager.enableBatched = true;
		PatternLayerManager.enableBatchedStrict = false;
		BatchedPatternRenderer.batchedDispatchCount.set(0);
		BatchedPatternLayerRenderer.fallbackCount.set(0);

		int warmupTicks = 5;
		int timedTicks = 120;
		int bufferSize = TICK_BUFFER_SIZE;
		int totalTicks = warmupTicks + timedTicks;
		int frames = bufferSize * totalTicks;

		List<Long> allCallNanos = Collections.synchronizedList(new ArrayList<>());
		PatternLayerManager.perCallTimingObserver = allCallNanos::add;

		Heap heap = new Heap(8 * 1024 * 1024);
		try {
			AudioScene<?> scene = pattern(1, 1);
			scene.setPatternActivityBias(1.0);

			heap.wrap(() -> {
				AudioScenePopulation pop = singleGenomePopulation(scene);
				pop.generate(PATTERN_CHANNEL, frames, bufferSize,
						() -> new File("results/batched-dispatch-sustained-"
								+ KeyUtils.generateKey() + ".wav").getPath(),
						result -> log("Generated " + result.getOutputPath())).run();
				return null;
			}).call();
		} finally {
			PatternLayerManager.perCallTimingObserver = null;
			heap.destroy();
		}

		List<Long> snapshot;
		synchronized (allCallNanos) {
			snapshot = new ArrayList<>(allCallNanos);
		}
		if (snapshot.size() < totalTicks) {
			Assert.fail(
					"PatternLayerManager.sum fired only " + snapshot.size()
							+ " times across " + frames + " frames (expected at "
							+ "least " + totalTicks + ", one per buffer tick). "
							+ "This likely means the runner did not include the "
							+ "pattern channel in the mixdown.");
		}
		List<Long> timed = snapshot.subList(warmupTicks, snapshot.size());
		reportSustainedRate(timed, bufferSize);
	}

	/**
	 * Builds and reports the percentile statistics for the timed per-call
	 * samples gathered via {@link PatternLayerManager#perCallTimingObserver}.
	 *
	 * <p>Each sample is the wall-clock cost of a single
	 * {@link PatternLayerManager#sum} call (the batched-dispatch boundary).
	 * Reports: total compute / total audio (steady-state ratio), p50/p95/p99
	 * per-call costs, worst-case per-call cost, and the cold-vs-hot delta
	 * (first call vs median of the rest).</p>
	 *
	 * <p>Asserts three things — in order of strictness:</p>
	 * <ol>
	 *   <li>{@link BatchedPatternRenderer#batchedDispatchCount} advanced at
	 *       least once (the no-op-measurement guard — without this assertion a
	 *       test that never reaches the batched path still produces a
	 *       favorable ratio by measuring nothing).</li>
	 *   <li>{@link BatchedPatternLayerRenderer#fallbackCount} stayed at zero
	 *       (every overlapping note had populated batched inputs).</li>
	 *   <li>The steady-state ratio is below 1.0 (real-time feasibility).</li>
	 * </ol>
	 */
	private void reportSustainedRate(List<Long> tickNanos, int bufferSize) {
		int n = tickNanos.size();
		long total = 0L;
		for (long t : tickNanos) total += t;
		long audioNanosPerTick = (long) (bufferSize * 1_000_000_000.0 / OutputLine.sampleRate);
		long totalAudio = audioNanosPerTick * n;
		double ratio = total / (double) totalAudio;

		List<Long> sorted = new ArrayList<>(tickNanos);
		Collections.sort(sorted);
		long p50 = sorted.get(n / 2);
		long p95 = sorted.get(Math.min(n - 1, (int) (n * 0.95)));
		long p99 = sorted.get(Math.min(n - 1, (int) (n * 0.99)));
		long worst = sorted.get(n - 1);
		long coldTick = tickNanos.get(0);
		long hotTick = sorted.get(n / 2);

		long batchedDispatches = BatchedPatternRenderer.batchedDispatchCount.get();
		long fallbacks = BatchedPatternLayerRenderer.fallbackCount.get();

		log("sustainedRateWallClockMeasurement"
				+ " calls=" + n
				+ " bufferSize=" + bufferSize
				+ " sampleRate=" + OutputLine.sampleRate
				+ " totalComputeMs=" + String.format("%.2f", total / 1_000_000.0)
				+ " totalAudioMs=" + String.format("%.2f", totalAudio / 1_000_000.0)
				+ " ratio=" + String.format("%.4f", ratio)
				+ " p50Ms=" + String.format("%.3f", p50 / 1_000_000.0)
				+ " p95Ms=" + String.format("%.3f", p95 / 1_000_000.0)
				+ " p99Ms=" + String.format("%.3f", p99 / 1_000_000.0)
				+ " worstMs=" + String.format("%.3f", worst / 1_000_000.0)
				+ " coldCallMs=" + String.format("%.3f", coldTick / 1_000_000.0)
				+ " hotCallMs=" + String.format("%.3f", hotTick / 1_000_000.0)
				+ " batchedDispatches=" + batchedDispatches
				+ " fallbacks=" + fallbacks);

		Assert.assertTrue(
				"Sustained-rate run must exercise the batched dispatch path; "
						+ "batchedDispatchCount stayed at 0 across " + n + " calls "
						+ "(fallbacks=" + fallbacks + "). A non-zero fallback count "
						+ "with batchedDispatches=0 means PatternFeatures.render WAS "
						+ "invoked, but every overlapping note lacked populated "
						+ "batched inputs — typically because ScaleTraversalStrategy"
						+ ".populateBatchedInputs failed to extract the leaf audio "
						+ "(e.g. PatternNote.combineLayers throws "
						+ "UnsupportedOperationException for noteDuration < 0). "
						+ "A zero fallback count means the renderer was never "
						+ "invoked at all — likely a scene-wiring/channel-mismatch "
						+ "issue. Either way the ratio measures something other "
						+ "than the batched path.",
				batchedDispatches > 0);
		Assert.assertEquals(
				"Fallback counter must stay at zero across the sustained run; "
						+ "delta=" + fallbacks + " — every overlapping note must "
						+ "have its batched inputs populated by "
						+ "ScaleTraversalStrategy.populateBatchedInputs",
				0L, fallbacks);
		Assert.assertTrue(
				"Sustained-rate ratio must be < 1.0 over " + n + " calls; ratio="
						+ String.format("%.4f", ratio),
				ratio < 1.0);
	}

	/**
	 * Builds a minimal {@link AudioScenePopulation} with a small genome count
	 * to keep the test runtime bounded. Genomes are sourced from the scene
	 * itself so their parameter count matches the scene's internal
	 * {@link org.almostrealism.heredity.ProjectedGenome} shape (16 params,
	 * not the 8 used by the parent class' generic test fixtures).
	 */
	private AudioScenePopulation miniPopulation(AudioScene<?> scene) {
		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		for (int i = 0; i < TEST_GENOME_COUNT; i++) {
			genomes.add(scene.getGenome().random());
		}
		AudioScenePopulation pop = new AudioScenePopulation(scene, genomes);
		pop.init(genomes.get(0), new MultiChannelAudioOutput(
				new WaveOutput(() -> null, 24, true)), List.of(PATTERN_CHANNEL), TICK_BUFFER_SIZE);
		return pop;
	}

	/**
	 * Builds an {@link AudioScenePopulation} with a single genome; used by
	 * the sustained-rate benchmark which runs ticks directly rather than
	 * iterating genomes. Same scene-genome-sourced approach as
	 * {@link #miniPopulation}.
	 */
	private AudioScenePopulation singleGenomePopulation(AudioScene<?> scene) {
		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		genomes.add(scene.getGenome().random());
		return new AudioScenePopulation(scene, genomes);
	}
}
