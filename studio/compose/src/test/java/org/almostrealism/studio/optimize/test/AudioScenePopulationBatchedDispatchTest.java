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
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.TemporalCellular;
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
import org.junit.Test;

import java.io.File;
import java.io.IOException;
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
 * <h2>Local resource requirements</h2>
 *
 * <p>Requires the {@code Library/} directory and a {@code pattern-factory.json}
 * file to exist in the project's repository root (or one of its ancestors
 * walking up from the test's working directory). Tests
 * {@link Assume#assumeTrue} on those resources and skip silently otherwise.</p>
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

	/** Cached project root resolved on first access. */
	private static File projectRoot;

	private boolean savedEnableBatched;
	private boolean savedEnableBatchedStrict;

	/**
	 * Walks upward from the test's working directory looking for an ancestor
	 * directory that contains both {@code Library/} and {@code pattern-factory.json}.
	 * The result is cached statically; subsequent calls return the same value.
	 *
	 * <p>The lookup tolerates the typical maven test layout where the working
	 * directory is the per-module directory (e.g. {@code studio/compose}) while
	 * the resources live at the repository root.</p>
	 *
	 * @return the resolved project root directory, or {@code null} when
	 *         neither the current directory nor any ancestor contains the
	 *         required resources
	 */
	private static File resolveProjectRoot() {
		if (projectRoot != null) return projectRoot;
		File cursor = new File(".").getAbsoluteFile();
		while (cursor != null) {
			File library = new File(cursor, "Library");
			File patternFactory = new File(cursor, "pattern-factory.json");
			if (library.isDirectory() && patternFactory.isFile()) {
				projectRoot = cursor;
				return cursor;
			}
			cursor = cursor.getParentFile();
		}
		return null;
	}

	/**
	 * Checks both the standard local-destination paths and the resolved
	 * project-root paths. Skips the test when neither is reachable.
	 */
	@Before
	public void checkResources() {
		boolean hasLocal = new File(SystemUtils.getLocalDestination("Library")).exists()
				&& new File(SystemUtils.getLocalDestination("pattern-factory.json")).exists();
		boolean hasProjectRoot = resolveProjectRoot() != null;
		Assume.assumeTrue("Library and pattern-factory.json required either at the "
						+ "local destination or in a project-root ancestor of the test cwd",
				hasLocal || hasProjectRoot);
	}

	/**
	 * Constructs a minimal {@link AudioScene} with absolute paths resolved
	 * against either the local-destination or the project-root, so the scene
	 * works regardless of where surefire runs the test from.
	 *
	 * @param sources       audio source channel count
	 * @param delayLayers   delay layer count for the mixdown pipeline
	 * @return the populated scene
	 */
	private AudioScene<?> pattern(int sources, int delayLayers) {
		try {
			AudioScene<?> scene = new AudioScene<>(120, sources, delayLayers, OutputLine.sampleRate);
			scene.setTotalMeasures(16);
			scene.setTuning(new DefaultKeyboardTuning());

			scene.loadPatterns(resolvePatternFactory().getAbsolutePath());
			scene.setLibraryRoot(new FileWaveDataProviderNode(resolveLibrary()));

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
	 * Resolves the absolute path to {@code pattern-factory.json}, preferring
	 * the local-destination path when available and falling back to the
	 * project-root copy otherwise.
	 */
	private File resolvePatternFactory() {
		File local = new File(SystemUtils.getLocalDestination("pattern-factory.json"));
		if (local.isFile()) return local;
		File root = resolveProjectRoot();
		if (root == null) {
			throw new IllegalStateException("pattern-factory.json not found");
		}
		return new File(root, "pattern-factory.json");
	}

	/**
	 * Resolves the absolute path to the {@code Library/} directory, preferring
	 * the local-destination path when available and falling back to the
	 * project-root copy otherwise.
	 */
	private File resolveLibrary() {
		File local = new File(SystemUtils.getLocalDestination("Library"));
		if (local.isDirectory()) return local;
		File root = resolveProjectRoot();
		if (root == null) {
			throw new IllegalStateException("Library not found");
		}
		return new File(root, "Library");
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
						+ batchedDelta,
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
	 * Sustained-rate wall-clock measurement. Runs at least 100 ticks against
	 * a real AudioScene with {@code AR_PATTERN_BATCHED=true} and reports the
	 * steady-state ratio, p50/p95/p99 per-tick costs, and worst-case tick
	 * cost. Gated behind {@code AR_PATTERN_BATCHED_SUSTAINED_BENCHMARK=true}
	 * so it only runs when measuring deliberately.
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

		Heap heap = new Heap(8 * 1024 * 1024);
		try {
			AudioScene<?> scene = pattern(1, 1);
			scene.setPatternActivityBias(1.0);

			List<Long> tickNanos = new ArrayList<>(timedTicks);

			heap.wrap(() -> {
				AudioScenePopulation pop = singleGenomePopulation(scene);
				MultiChannelAudioOutput sinkOutput = new MultiChannelAudioOutput(
						new WaveOutput(() -> null, 24, true));
				pop.init(pop.getGenomes().get(0), sinkOutput, List.of(PATTERN_CHANNEL), bufferSize);
				TemporalCellular cells = pop.enableGenome(0);
				Runnable setup = cells.setup().get();
				Runnable tick = cells.tick().get();
				setup.run();

				for (int w = 0; w < warmupTicks; w++) {
					tick.run();
				}

				for (int i = 0; i < timedTicks; i++) {
					long t0 = System.nanoTime();
					tick.run();
					tickNanos.add(System.nanoTime() - t0);
				}

				return null;
			}).call();

			reportSustainedRate(tickNanos, bufferSize);
		} finally {
			heap.destroy();
		}
	}

	/**
	 * Builds and reports the percentile statistics for the timed-tick samples.
	 *
	 * <p>Reports: total compute / total audio (steady-state ratio), p50/p95/p99
	 * per-tick costs, worst-case tick cost, and the cold-vs-hot delta (first
	 * tick vs median of the rest).</p>
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

		log("sustainedRateWallClockMeasurement"
				+ " ticks=" + n
				+ " bufferSize=" + bufferSize
				+ " sampleRate=" + OutputLine.sampleRate
				+ " totalComputeMs=" + String.format("%.2f", total / 1_000_000.0)
				+ " totalAudioMs=" + String.format("%.2f", totalAudio / 1_000_000.0)
				+ " ratio=" + String.format("%.4f", ratio)
				+ " p50Ms=" + String.format("%.3f", p50 / 1_000_000.0)
				+ " p95Ms=" + String.format("%.3f", p95 / 1_000_000.0)
				+ " p99Ms=" + String.format("%.3f", p99 / 1_000_000.0)
				+ " worstMs=" + String.format("%.3f", worst / 1_000_000.0)
				+ " coldTickMs=" + String.format("%.3f", coldTick / 1_000_000.0)
				+ " hotTickMs=" + String.format("%.3f", hotTick / 1_000_000.0)
				+ " batchedDispatches=" + BatchedPatternRenderer.batchedDispatchCount.get()
				+ " fallbacks=" + BatchedPatternLayerRenderer.fallbackCount.get());

		Assert.assertTrue(
				"Sustained-rate ratio must be < 1.0 over " + n + " ticks; ratio="
						+ String.format("%.4f", ratio),
				ratio < 1.0);
		Assert.assertEquals(
				"Fallback counter must stay at zero across the sustained run; "
						+ "delta=" + BatchedPatternLayerRenderer.fallbackCount.get(),
				0L, BatchedPatternLayerRenderer.fallbackCount.get());
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
