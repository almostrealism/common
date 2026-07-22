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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heap-free twin of {@link ArrangementGenerationMemoryTest}: the same optimizer-shaped
 * genome loop — every genome's full multi-channel arrangement rendered through
 * {@link StableDurationHealthComputation#computeHealth()} on a reused runner — with no
 * {@link org.almostrealism.hardware.mem.Heap} active at any point.
 *
 * <p>This is the evidence test for whether the optimizer paths need a Heap at all.
 * Without one, per-invocation device buffers are unreferenced garbage reclaimed by
 * ordinary GC (plus the {@code gcBeforeGenome} full-GC hint at each genome boundary),
 * and the heap's own costs disappear: the root arena
 * ({@code AudioSceneOptimizer.DEFAULT_HEAP_SIZE} = 384M values = 1.5GB device memory
 * at FP32) and a stage-sized allocation for every per-note {@code Heap.stage} scope.
 * If this test passes where the heap-wrapped gate passes — same memory scale, same
 * genome count, non-silent output — the heap provides no necessary service to this
 * use-case.</p>
 *
 * <p>Logs each provider's live native bytes after every genome so the cross-genome
 * trajectory (bounded vs. accumulating) is visible alongside the pass/fail result.</p>
 */
public class ArrangementGenerationNoHeapTest extends AudioSceneTestBase {

	/** Tempo for the rendered arrangement (matches {@link ArrangementGenerationMemoryTest}). */
	private static final double BPM = 120.0;

	/** Total measures in the arrangement. */
	private static final int MEASURES = 64;

	/** Number of distinct genomes rendered in sequence (matches the heap-wrapped gate). */
	private static final int GENOME_COUNT = 8;

	/** Base seed; genome {@code i} is deterministically seeded {@code GENOME_SEED_BASE + i}. */
	private static final long GENOME_SEED_BASE = 58;

	/** Per-genome evaluation duration cap in seconds. */
	private static final long MAX_DURATION_SECONDS = 8;

	/**
	 * Renders {@link #GENOME_COUNT} genomes' full multi-channel arrangements in sequence
	 * with no Heap active, writing each mix to {@code results/noheap-arrangement-N.wav}
	 * and logging the per-genome live native memory trajectory.
	 *
	 * @throws Exception if the render loop fails (a {@code HardwareException}
	 *                   "Memory Max Reached" here would mean GC reclamation cannot
	 *                   keep this path bounded without a Heap)
	 */
	@Test(timeout = 1_800_000)
	@TestDepth(2)
	public void generateArrangementsWithoutHeap() throws Exception {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping generateArrangementsWithoutHeap - need the curated library ("
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
				+ " bpm=" + BPM + " measures=" + MEASURES
				+ " maxDurationSec=" + MAX_DURATION_SECONDS + " heap=none");

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
				"results/noheap-arrangement-" + index.incrementAndGet() + ".wav");

		AudioScenePopulation pop = new AudioScenePopulation(scene, genomes);
		pop.init(pop.getGenomes().get(0), health.getOutput(), null, health.getBatchSize());

		try {
			for (int i = 0; i < genomes.size(); i++) {
				TemporalCellular organ = pop.enableGenome(i);
				try {
					health.setTarget(organ);
					health.computeHealth();
				} finally {
					health.reset();
					pop.disableGenome();
				}

				logNativeMemory("genome " + i);
			}
		} finally {
			pop.destroy();
		}

		File first = new File("results/noheap-arrangement-1.wav");
		Assert.assertTrue("No arrangement audio produced (expected " + first.getPath() + ")",
				first.exists());
		double peak = peakAmplitude(first.getPath());
		log("firstArrangementPeakAmplitude=" + peak + " file=" + first.getAbsolutePath());
		Assert.assertTrue("First generated arrangement is silent (peak=" + peak + ")", peak > 1e-3);
	}

	/**
	 * Logs each hardware provider's live native memory after a forced GC, so the
	 * per-genome trajectory shows whether GC reclamation keeps this heap-free
	 * path bounded.
	 *
	 * @param label marker for correlating the report with the genome loop
	 */
	private void logNativeMemory(String label) {
		System.gc();
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		for (MemoryProvider<? extends Memory> provider :
				Hardware.getLocalHardware().getDataContext().getMemoryProviders()) {
			if (!(provider instanceof HardwareMemoryProvider)) continue;
			HardwareMemoryProvider<?> hw = (HardwareMemoryProvider<?>) provider;
			long bytes = hw.getAllocated().stream()
					.mapToLong(NativeRef::getSize).sum();
			log("nativeMemory label=" + label + " provider=" + provider.getName()
					+ " liveBlocks=" + hw.getAllocatedCount()
					+ " liveBytes=" + bytes + " (" + (bytes / 1024 / 1024) + "mb)");
		}
	}
}
