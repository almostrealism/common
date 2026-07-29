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

package org.almostrealism.spatial.test;

import org.almostrealism.music.pattern.PatternSystemManager;
import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.spatial.ArrangementGenerationProcess;
import org.almostrealism.spatial.GenomicNetwork;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.AudioSceneRealtimeRunner;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the arrangement generation process headlessly from a fresh start: no
 * persisted networks and no persisted population. Every score delivery, record
 * attachment, and persistence round trip is observed directly, and any error the
 * process reports fails the test with the error attached, so a failure an
 * interactive client could only surface through its error reporting is
 * reproduced here as a plain assertion failure.
 */
public class ArrangementGenerationProcessTest extends TestSuiteBase {

	/** The curated sample library the scene draws from. */
	private static final String SAMPLES = "/Users/Shared/Music/Samples";

	/** The curated pattern factory the scene draws from. */
	private static final String PATTERN_FACTORY = "/Users/Shared/Music/pattern-factory.json";

	/** Persisted scene settings, shared with the compose render tests. */
	private static final String SCENE_SETTINGS =
			"../compose/results/pdsl-cutover/scene-settings.json";

	/** Evaluation seconds per genome. */
	private static final int MAX_DURATION_SECONDS = 8;

	/**
	 * Runs one optimization cycle over a two-genome population from a fresh start
	 * and asserts every delivered score attaches to its record with a full set of
	 * stems, surviving the process's own persistence round trip.
	 *
	 * @throws Exception if the scene cannot be loaded or the run fails
	 */
	@Test(timeout = 900_000)
	@TestDepth(2)
	public void processAttachesScoredNetworksWithStems() throws Exception {
		File library = new File(SAMPLES);
		File patternFactory = new File(PATTERN_FACTORY);
		if (!library.exists() || !patternFactory.exists()) {
			log("Skipping processAttachesScoredNetworksWithStems - no curated library");
			return;
		}

		MixdownManager.enableMainFilterUp = true;
		MixdownManager.enableEfx = true;
		MixdownManager.enableEfxFilters = true;
		MixdownManager.enableReverb = true;
		MixdownManager.enablePdslMixdown = true;
		PatternSystemManager.enableWarnings = false;
		AudioSceneRealtimeRunner.renderAheadSlots = 24;

		PopulationOptimizer.popSize = 2;
		PopulationOptimizer.maxChildren = 2;

		new File("results").mkdirs();
		File networksFile = new File("results/process-networks.xml");
		networksFile.delete();
		new File(AudioSceneOptimizer.POPULATION_FILE).delete();

		File settings = new File(SCENE_SETTINGS);
		AudioScene<?> scene = AudioScene.load(
				settings.exists() ? settings.getAbsolutePath() : null,
				patternFactory.getAbsolutePath(),
				library.getAbsolutePath(), 120.0, 44100);
		scene.setTotalMeasures(64);

		ArrangementGenerationProcess process =
				new ArrangementGenerationProcess(networksFile.getPath());

		List<GenomicNetwork> attached = new ArrayList<>();
		List<AudioHealthScore> delivered = new ArrayList<>();
		List<Exception> errors = new ArrayList<>();

		process.setScoreListener((network, score) -> {
			attached.add(network);
			delivered.add(score);
		});
		process.setErrorListener(e -> {
			warn("processError " + e.getClass().getSimpleName(), e);
			errors.add(e);
		});

		try {
			process.prepare(scene, 1);
			((StableDurationHealthComputation) process.getOptimizer()
					.getHealthComputation()).setMaxDuration(MAX_DURATION_SECONDS);
			process.run();

			Assert.assertTrue("The process reported " + errors.size() + " error(s);"
							+ " first: " + (errors.isEmpty() ? "" : errors.get(0)),
					errors.isEmpty());
			Assert.assertFalse("No health scores were delivered", delivered.isEmpty());

			int expected = scene.getChannelCount() + 1;

			for (int i = 0; i < delivered.size(); i++) {
				Assert.assertNotNull("Delivered score " + i
						+ " did not attach to any record", attached.get(i));
				AudioHealthScore score = delivered.get(i);
				Assert.assertNotNull("Delivered score " + i + " carries no stems",
						score.getStems());
				Assert.assertEquals("Delivered score " + i
								+ " must carry one stem per audio channel",
						expected, score.getStems().size());

				for (String path : score.getStems()) {
					Assert.assertTrue("Missing stem file " + path,
							new File(path).exists());
				}
			}

			long scored = process.getNetworks().stream()
					.filter(g -> g.getHealthScore() != null).count();
			Assert.assertTrue("No scored records survived the persistence round trip",
					scored > 0);
			log("attachedScores=" + attached.size()
					+ " persistedScoredNetworks=" + scored);
		} finally {
			process.destroy();
		}
	}
}
