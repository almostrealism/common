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
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;
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
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic companion to {@link ArrangementGenerationMemoryTest} that attributes the
 * device memory retained across the optimizer's genome loop to its allocation sites.
 *
 * <p>After each genome's full multi-channel render (the same
 * {@link StableDurationHealthComputation#computeHealth()} loop as the desktop optimizer),
 * this test forces a GC and then reports, for every {@link HardwareMemoryProvider}, the
 * total live native bytes and the top allocation sites by retained bytes
 * (via {@link HardwareMemoryProvider#getAllocated()}). Allocation sites are available
 * when the run sets {@code AR_HARDWARE_ALLOCATION_TRACE_FRAMES} (e.g. 24); without it
 * every block groups under a single unattributed entry.</p>
 *
 * <p>Because the report runs after a forced GC, any site whose retained bytes grow
 * monotonically across genomes is holding memory through strong references — the
 * pinned-live accumulation that ends in {@code HardwareException: Memory Max Reached}
 * on the desktop — rather than garbage awaiting collection.</p>
 */
public class ArrangementMemoryAttributionTest extends AudioSceneTestBase {

	/** Tempo for the rendered arrangement (matches {@link ArrangementGenerationMemoryTest}). */
	private static final double BPM = 120.0;

	/** Total measures in the arrangement. */
	private static final int MEASURES = 64;

	/** Genomes rendered in sequence; four is enough to expose per-genome growth. */
	private static final int GENOME_COUNT = 4;

	/** Base seed; genome {@code i} is deterministically seeded {@code GENOME_SEED_BASE + i}. */
	private static final long GENOME_SEED_BASE = 58;

	/** Per-genome evaluation duration cap in seconds. */
	private static final long MAX_DURATION_SECONDS = 8;

	/** Allocation sites reported per provider per genome. */
	private static final int TOP_SITES = 12;

	/**
	 * Renders {@link #GENOME_COUNT} genomes through the desktop optimizer's per-genome render
	 * path and logs a per-genome native-allocation report grouped by allocation site.
	 *
	 * @throws Exception if the heap-wrapped render loop fails
	 */
	@Test(timeout = 1_800_000)
	@TestDepth(2)
	public void attributeArrangementMemory() throws Exception {
		File library = getSamplesDir();
		File patternFactory = new File(PATTERN_FACTORY);
		if (library == null || !patternFactory.exists()) {
			log("Skipping attributeArrangementMemory - need the curated library ("
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
		log("scene channels=" + channels + " genomes=" + GENOME_COUNT);

		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		for (int i = 0; i < GENOME_COUNT; i++) {
			genomes.add(fixedGenome(scene, GENOME_SEED_BASE + i));
		}

		StableDurationHealthComputation health =
				new StableDurationHealthComputation(channels, true);
		health.setMaxDuration(MAX_DURATION_SECONDS);

		AudioScenePopulation pop = new AudioScenePopulation(scene, genomes);
		pop.init(pop.getGenomes().get(0), health.getOutput(), null, health.getBatchSize());

		renderGenome(health, pop, 0);
		reportAllocations("warmup");

		Heap heap = new Heap(AudioSceneOptimizer.DEFAULT_HEAP_SIZE);
		try {
			heap.wrap(() -> {
				for (int i = 0; i < genomes.size(); i++) {
					renderGenome(health, pop, i);
					reportAllocations("genome " + i);
				}
				return null;
			}).call();
		} finally {
			heap.destroy();
			pop.destroy();
		}
	}

	/**
	 * Renders one genome's full multi-channel arrangement through {@code computeHealth()}
	 * and resets for the next, mirroring {@link ArrangementGenerationMemoryTest}.
	 *
	 * @param health the health computation driving the render
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

	/**
	 * Logs total live native bytes and the top allocation sites for every hardware
	 * memory provider, after forcing a GC so that only strongly-referenced blocks remain.
	 *
	 * @param label marker for correlating the report with the genome loop
	 */
	private void reportAllocations(String label) {
		System.gc();
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		for (MemoryProvider<? extends Memory> provider :
				Hardware.getLocalHardware().getDataContext().getMemoryProviders()) {
			if (!(provider instanceof HardwareMemoryProvider)) continue;

			List<? extends NativeRef<?>> refs =
					((HardwareMemoryProvider<?>) provider).getAllocated();
			long total = refs.stream().mapToLong(NativeRef::getSize).sum();
			log("allocationReport label=" + label + " provider=" + provider.getName()
					+ " liveBlocks=" + refs.size() + " liveBytes=" + total
					+ " (" + (total / 1024 / 1024) + "mb)");

			Map<String, long[]> sites = new HashMap<>();
			for (NativeRef<?> ref : refs) {
				long[] entry = sites.computeIfAbsent(siteOf(ref), k -> new long[2]);
				entry[0] += ref.getSize();
				entry[1]++;
			}

			sites.entrySet().stream()
					.sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) ->
							e.getValue()[0]).reversed())
					.limit(TOP_SITES)
					.forEach(e -> log("allocationSite label=" + label
							+ " bytes=" + e.getValue()[0]
							+ " (" + (e.getValue()[0] / 1024 / 1024) + "mb)"
							+ " count=" + e.getValue()[1]
							+ " site=" + e.getKey()));

			logExemplar(label, refs, sites);
		}
	}

	/**
	 * Logs the full captured stack of one block from each of the three largest allocation
	 * sites, so the complete call chain (not just the site key) is visible for the
	 * dominant accumulators.
	 *
	 * @param label marker for correlating the report with the genome loop
	 * @param refs  the live references
	 * @param sites the site aggregation produced by {@link #reportAllocations}
	 */
	private void logExemplar(String label, List<? extends NativeRef<?>> refs, Map<String, long[]> sites) {
		List<String> top = sites.entrySet().stream()
				.sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) ->
						e.getValue()[0]).reversed())
				.limit(3)
				.map(Map.Entry::getKey).toList();

		for (String site : top) {
			for (NativeRef<?> ref : refs) {
				if (!site.equals(siteOf(ref))) continue;

				StackTraceElement[] trace = ref.getAllocationStackTrace();
				if (trace == null) break;
				StringBuilder chain = new StringBuilder();
				for (int i = 0; i < trace.length; i++) {
					if (i > 0) chain.append(" <- ");
					chain.append(trace[i].getClassName()
									.substring(trace[i].getClassName().lastIndexOf('.') + 1))
							.append(".").append(trace[i].getMethodName())
							.append(":").append(trace[i].getLineNumber());
				}
				log("exemplarStack label=" + label + " site=" + site + " chain=" + chain);
				break;
			}
		}
	}

	/**
	 * Derives a stable allocation-site key from the reference's captured stack trace:
	 * the first frame outside the memory-allocation plumbing, plus its caller.
	 *
	 * @param ref the reference whose birth site is wanted
	 * @return a site key, or {@code "(untraced)"} when trace capture is disabled
	 */
	private String siteOf(NativeRef<?> ref) {
		StackTraceElement[] trace = ref.getAllocationStackTrace();
		if (trace == null || trace.length == 0) return "(untraced)";

		int first = -1;
		for (int i = 0; i < trace.length; i++) {
			String cls = trace[i].getClassName();
			boolean plumbing = cls.startsWith("java.")
					|| cls.contains(".hardware.")
					|| cls.endsWith(".PackedCollection")
					|| cls.endsWith(".Bytes");
			if (!plumbing) {
				first = i;
				break;
			}
		}

		if (first < 0) first = Math.min(3, trace.length - 1);
		StackTraceElement a = trace[first];
		String site = a.getClassName().substring(a.getClassName().lastIndexOf('.') + 1)
				+ "." + a.getMethodName() + ":" + a.getLineNumber();
		if (first + 1 < trace.length) {
			StackTraceElement b = trace[first + 1];
			site += " <- " + b.getClassName().substring(b.getClassName().lastIndexOf('.') + 1)
					+ "." + b.getMethodName() + ":" + b.getLineNumber();
		}
		return site;
	}
}
