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

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.music.pattern.BatchedPatternLayerRenderer;
import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Attributes the one-time setup cost of the real-time PDSL runner to its individual stages,
 * so the front-loaded work can be pinpointed rather than guessed at.
 *
 * <p>{@code GenerateAudioFileTest} reports the setup phase as a single {@code setupSeconds}
 * number, and {@code KernelCacheReuseAcrossScenesTest} showed that number is per-scene
 * rendering work, not kernel compilation (it does not shrink when every kernel is already
 * compiled and cached). This harness walks the setup {@link OperationList} the same way
 * {@code AudioScenePdslBenchmarkTest#pdslTickStageTiming} walks the tick list — timing each
 * child's build ({@code get()}) and run separately — and captures the
 * {@link BatchedPatternLayerRenderer} gather/marshal/eval counter deltas per stage, splitting
 * each first-buffer render into note preparation ({@code getNoteDestinations}), input
 * marshalling, and kernel evaluation.</p>
 *
 * <p>A short run of steady-state ticks follows for contrast, and the streamed output is
 * asserted non-silent so the measured setup is the one that actually produces audio.</p>
 */
public class PdslSetupBreakdownTest extends AudioSceneTestBase {

	/** Tempo for the pinned arrangement (matches {@code GenerateAudioFileTest}). */
	private static final double BENCH_BPM = 120.0;

	/** Total measures in the pinned arrangement (matches {@code GenerateAudioFileTest}). */
	private static final int BENCH_MEASURES = 64;

	/** Genome seed: the dense curated genome the other harnesses render (~1126 elements). */
	private static final long GENOME_SEED = 58;

	/** Frames per buffer (matches {@code GenerateAudioFileTest}). */
	private static final int BUFFER_SIZE = 8192;

	/** Steady-state ticks run after setup to contrast with the setup stage costs. */
	private static final int CONTRAST_TICKS = 8;

	/**
	 * Times every stage of the PDSL runner's setup individually on the pinned dense scene
	 * and logs, per stage, the build and run wall time plus the gather/marshal/eval split
	 * for any pattern rendering the stage performed.
	 *
	 * @throws IOException if the curated scene cannot be loaded
	 */
	@Test(timeout = 900_000)
	@TestDepth(1)
	@TestProperties(excludeProfiles = TestUtils.PIPELINE)
	public void setupBreakdown() throws IOException {
		File library = requireCuratedLibrary();
		File patternFactory = new File(PATTERN_FACTORY);

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		MixdownManager.enablePdslMixdown = true;
		PatternSystemManager.enableWarnings = false;
		AudioSceneRealtimeRunner.renderAheadSlots = 24;

		AudioScene<?> scene = loadCuratedScene(library, patternFactory, BENCH_BPM, BENCH_MEASURES);
		applyGenome(scene, GENOME_SEED);
		log("scene elements=" + countElements(scene) + " seed=" + GENOME_SEED
				+ " bpm=" + BENCH_BPM + " measures=" + BENCH_MEASURES
				+ " bufferSize=" + BUFFER_SIZE);

		File scratch = new File("results/setup-breakdown-scratch.wav");
		scratch.getParentFile().mkdirs();
		WaveOutput out = new WaveOutput(() -> scratch, 24, true);

		long buildStart = System.nanoTime();
		TemporalCellular runner = scene.runnerRealTime(
				new MultiChannelAudioOutput(out), null, BUFFER_SIZE);
		log("runnerBuildMs=" + fmt((System.nanoTime() - buildStart) / 1e6));

		try {
			Supplier<Runnable> setupSupplier = runner.setup();
			Assert.assertTrue("Expected the PDSL setup to be an OperationList",
					setupSupplier instanceof OperationList);

			List<String> names = new ArrayList<>();
			List<Supplier<Runnable>> stages = new ArrayList<>();
			flatten((OperationList) setupSupplier, "", names, stages);

			BatchedPatternLayerRenderer.resetCounters();
			double totalMs = 0;
			for (int s = 0; s < stages.size(); s++) {
				long gatherBase = BatchedPatternLayerRenderer.gatherNanos.get();
				long marshalBase = BatchedPatternLayerRenderer.marshalNanos.get();
				long evalBase = BatchedPatternLayerRenderer.evalNanos.get();

				long getStart = System.nanoTime();
				Runnable op = stages.get(s).get();
				double getMs = (System.nanoTime() - getStart) / 1e6;

				long runStart = System.nanoTime();
				op.run();
				double runMs = (System.nanoTime() - runStart) / 1e6;

				totalMs += getMs + runMs;
				log("stage=" + s
						+ " getMs=" + fmt(getMs)
						+ " runMs=" + fmt(runMs)
						+ " gatherMs=" + fmt((BatchedPatternLayerRenderer.gatherNanos.get() - gatherBase) / 1e6)
						+ " marshalMs=" + fmt((BatchedPatternLayerRenderer.marshalNanos.get() - marshalBase) / 1e6)
						+ " evalMs=" + fmt((BatchedPatternLayerRenderer.evalNanos.get() - evalBase) / 1e6)
						+ " name=" + names.get(s));
			}
			log("setupTotalMs=" + fmt(totalMs) + " stages=" + stages.size());

			Runnable tick = runner.tick().get();
			for (int i = 0; i < CONTRAST_TICKS; i++) {
				long tickStart = System.nanoTime();
				tick.run();
				log("tick=" + i + " tickMs=" + fmt((System.nanoTime() - tickStart) / 1e6));
			}

			out.write().get().run();
			assertFiniteNonSilent("setupBreakdown", readWavSamples(scratch));
		} finally {
			out.reset();
			runner.reset();
		}
	}

	/**
	 * Flattens the setup {@link OperationList} into leaf stages in execution order. The
	 * top-level children of the runner's setup are expanded one level (so the render-cell
	 * preparation inside the {@code AudioScene Setup} list is visible stage by stage);
	 * anything deeper runs as a single stage.
	 *
	 * @param list   the setup operation list
	 * @param prefix name prefix identifying the parent list ({@code ""} at the top level)
	 * @param names  receives one display name per stage
	 * @param stages receives the stage suppliers in execution order
	 */
	private void flatten(OperationList list, String prefix,
						 List<String> names, List<Supplier<Runnable>> stages) {
		int i = 0;
		for (Supplier<Runnable> child : list) {
			String name = prefix + "[" + i + "] " + (child instanceof OperationList ?
					((OperationList) child).getDescription() : child.getClass().getSimpleName());
			if (child instanceof OperationList && prefix.isEmpty()) {
				flatten((OperationList) child, name + " / ", names, stages);
			} else {
				names.add(name);
				stages.add(child);
			}
			i++;
		}
	}

	/**
	 * Formats a millisecond value with two decimal places.
	 *
	 * @param value the value to format
	 * @return the formatted value
	 */
	private String fmt(double value) {
		return String.format("%.2f", value);
	}
}
