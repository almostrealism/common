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

package org.almostrealism.studio.pattern.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Memory proxy for the Rings desktop "Run <em>N</em> Cycle(s)" arrangement-generation button.
 *
 * <p>{@link GenerateAudioFileTest#generateAudioFile()} renders the full multi-channel PDSL
 * arrangement (all channels, full efx + reverb) for a <em>single</em> genome to a listenable WAV.
 * That is a faithful render of the audio, but its memory profile is <em>not</em> representative of
 * the desktop: pressing the arrangement button runs the optimizer, which evaluates <em>many</em>
 * genomes in sequence inside one {@code Heap.wrap(...)} — each rendered through the full
 * multi-channel arrangement via {@link StableDurationHealthComputation#computeHealth()} — so native
 * GPU memory accumulates <em>across</em> genome evaluations. A single-genome render never exercises
 * that accumulation.</p>
 *
 * <p>This test reproduces the accumulation faithfully by mirroring
 * {@code PopulationControlsController.iterate} in ringsdesktop:</p>
 * <ol>
 *   <li>load the curated multi-channel scene with the full effects chain enabled (as the desktop
 *       renders it), so the per-genome working set matches production;</li>
 *   <li>build an {@link AudioScenePopulation} over the <em>real</em> scene with several
 *       scene-shape-matched genomes. The existing optimizer/health tests
 *       ({@code AudioSceneOptimizerTest#healthTest},
 *       {@code StableDurationHealthComputationTest#samplesPopulationHealth}) are {@code knownIssue}
 *       only because they pass a {@code null} scene (NPE at {@code init}) or a mismatched
 *       {@code ProjectedGenome(8)}; {@link #fixedGenome} sizes each genome to the scene's genome
 *       shape so {@code assignGenome} never mismatches;</li>
 *   <li>{@code init(...)} the population across <em>all</em> channels (the {@code channels == null}
 *       overload);</li>
 *   <li>inside a single {@link Heap} of the desktop's {@code DEFAULT_HEAP_SIZE}, loop over the
 *       genomes calling {@code computeHealth()} on each — the exact per-genome render the optimizer
 *       runs — writing each genome's full mix to {@code results/generated-arrangement-N.wav} for
 *       listening review.</li>
 * </ol>
 *
 * <p>With adequate device memory the test passes and produces the review WAVs; run under a
 * memory-constrained backend (e.g. a low {@code AR_HARDWARE_MEMORY_SCALE} on Metal) it reproduces
 * the desktop's {@code MetalMemoryProvider} "Memory Max Reached" during a later genome's render.
 * That makes it a common-repo stand-in for what the desktop button actually does.</p>
 *
 * @see GenerateAudioFileTest
 * @see StableDurationHealthComputation
 * @see AudioScenePopulation
 */
public class ArrangementGenerationMemoryTest extends AudioSceneTestBase {

	/** Tempo for the rendered arrangement (matches {@link GenerateAudioFileTest}). */
	private static final double BPM = 120.0;

	/** Total measures in the arrangement. */
	private static final int MEASURES = 64;

	/**
	 * Number of distinct genomes rendered in sequence. The desktop optimizer evaluates a full
	 * population per cycle; eight is enough for cross-genome native-memory accumulation to be
	 * reproducible while keeping the test runnable, and is the tunable knob for how hard the proxy
	 * stresses memory.
	 */
	private static final int GENOME_COUNT = 8;

	/** Base seed; genome {@code i} is deterministically seeded {@code GENOME_SEED_BASE + i}. */
	private static final long GENOME_SEED_BASE = 58;

	/** Per-genome evaluation duration cap in seconds (the health computation's max render length). */
	private static final long MAX_DURATION_SECONDS = 8;

	/**
	 * Renders {@link #GENOME_COUNT} genomes' full multi-channel arrangements in sequence through the
	 * desktop optimizer's per-genome render path, accumulating device memory across the loop, and
	 * writes each genome's mix to {@code results/generated-arrangement-N.wav}.
	 *
	 * @throws Exception if the heap-wrapped render loop fails (including a genuine
	 *                   {@code HardwareException} "Memory Max Reached" when memory-constrained)
	 */
	@Test(timeout = 1_800_000)
	@TestDepth(2)
	public void generateArrangementsAcrossGenomes() throws Exception {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping generateArrangementsAcrossGenomes - need the curated library ("
					+ SAMPLES_PATH + ") and pattern factory (" + PATTERN_FACTORY + ")");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		MixdownManager.enablePdslMixdown = true;
		PatternSystemManager.enableWarnings = false;
		AudioSceneRealtimeRunner.renderAheadSlots = 24;

		AudioScene<?> scene = loadCuratedScene(library, patternFactory, BPM, MEASURES);
		int channels = scene.getChannelCount();
		log("scene channels=" + channels + " genomes=" + GENOME_COUNT
				+ " bpm=" + BPM + " measures=" + MEASURES + " maxDurationSec=" + MAX_DURATION_SECONDS);

		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		for (int i = 0; i < GENOME_COUNT; i++) {
			genomes.add(fixedGenome(scene, GENOME_SEED_BASE + i));
		}

		new File("results").mkdirs();
		AtomicInteger index = new AtomicInteger();

		StableDurationHealthComputation health =
				new StableDurationHealthComputation(channels, true);
		health.setMaxDuration(MAX_DURATION_SECONDS);
		health.setOutputFile(() ->
				"results/generated-arrangement-" + index.incrementAndGet() + ".wav");

		AudioScenePopulation pop = new AudioScenePopulation(scene, genomes);
		pop.init(pop.getGenomes().get(0), health.getOutput(), null, health.getBatchSize());

		renderGenome(health, pop, 0);

		Heap heap = new Heap(AudioSceneOptimizer.DEFAULT_HEAP_SIZE);
		try {
			heap.wrap(() -> {
				for (int i = 0; i < genomes.size(); i++) {
					renderGenome(health, pop, i);
					log("Rendered genome " + i);
				}
				return null;
			}).call();
		} finally {
			heap.destroy();
			pop.destroy();
		}

		File first = new File("results/generated-arrangement-1.wav");
		Assert.assertTrue("No arrangement audio produced (expected " + first.getPath() + ")",
				first.exists());
		double peak = peakAmplitude(first.getPath());
		log("firstArrangementPeakAmplitude=" + peak + " file=" + first.getAbsolutePath());
		Assert.assertTrue("First generated arrangement is silent (peak=" + peak + ")", peak > 1e-3);
	}

	/**
	 * Renders one genome's full multi-channel arrangement through {@code computeHealth()} and resets
	 * for the next. Invoked once <em>outside</em> the {@link Heap} to warm the render singletons
	 * ({@code AudioProcessingUtils} and {@code AudioSumProvider} throw when constructed while a Heap
	 * is active), then once per genome inside the heap-wrapped accumulation loop.
	 *
	 * @param health the health computation driving the render and the per-genome WAV output
	 * @param pop    the population supplying the reused genome runner
	 * @param i      the genome index to enable and render
	 */
	private void renderGenome(StableDurationHealthComputation health, AudioScenePopulation pop, int i) {
		TemporalCellular organ = pop.enableGenome(i);
		try {
			health.setTarget(organ);
			health.computeHealth();
		} finally {
			health.reset();
			pop.disableGenome();
		}
	}
}
