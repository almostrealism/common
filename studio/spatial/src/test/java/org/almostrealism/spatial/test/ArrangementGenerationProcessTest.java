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

import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.spatial.ArrangementGenerationProcess;
import org.almostrealism.spatial.GenomicNetwork;
import org.almostrealism.studio.AudioScene;
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
		if (!CuratedArrangementFixture.curatedSceneAvailable()) {
			log("Skipping processAttachesScoredNetworksWithStems - no curated library");
			return;
		}

		CuratedArrangementFixture.enableRealtimeMixdown();

		PopulationOptimizer.popSize = 2;
		PopulationOptimizer.maxChildren = 2;

		new File("results").mkdirs();
		File networksFile = new File("results/process-networks.xml");
		networksFile.delete();
		new File(AudioSceneOptimizer.POPULATION_FILE).delete();

		AudioScene<?> scene = CuratedArrangementFixture.loadCuratedScene(64);

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

	/**
	 * Runs three optimization cycles and verifies renders do not degrade across
	 * cycles: mixdown state that persists from one genome's render into the next
	 * (delay rings, filter memories) accumulates until every render clips in its
	 * first buffer and is terminated immediately by the health computation, which
	 * shows up as every score in the final cycle reporting only a moment of audio.
	 *
	 * @throws Exception if the scene cannot be loaded or the run fails
	 */
	@Test(timeout = 1_500_000)
	@TestDepth(2)
	public void rendersSurviveAcrossCycles() throws Exception {
		if (!CuratedArrangementFixture.curatedSceneAvailable()) {
			log("Skipping rendersSurviveAcrossCycles - no curated library");
			return;
		}

		CuratedArrangementFixture.enableRealtimeMixdown();

		PopulationOptimizer.popSize = 2;
		PopulationOptimizer.maxChildren = 2;

		new File("results").mkdirs();
		File networksFile = new File("results/process-networks.xml");
		networksFile.delete();
		new File(AudioSceneOptimizer.POPULATION_FILE).delete();

		AudioScene<?> scene = CuratedArrangementFixture.loadCuratedScene(64);

		ArrangementGenerationProcess process =
				new ArrangementGenerationProcess(networksFile.getPath());

		List<Long> cycleStarts = new ArrayList<>();
		List<AudioHealthScore> delivered = new ArrayList<>();

		process.setCycleListener(() ->
				cycleStarts.add((long) delivered.size()));
		process.setScoreListener((network, score) -> {
			delivered.add(score);
			log("cycleDelivery=" + delivered.size()
					+ " frames=" + score.getFrames()
					+ " score=" + score.getScore());
		});
		process.setErrorListener(e -> warn("processError", e));

		try {
			process.prepare(scene, 3);
			((StableDurationHealthComputation) process.getOptimizer()
					.getHealthComputation()).setMaxDuration(MAX_DURATION_SECONDS);
			process.run();

			Assert.assertTrue("Expected deliveries from three cycles",
					cycleStarts.size() >= 3 && !delivered.isEmpty());

			long laterCycleStart = cycleStarts.get(1);
			List<AudioHealthScore> later =
					delivered.subList((int) laterCycleStart, delivered.size());
			long best = later.stream()
					.mapToLong(AudioHealthScore::getFrames).max().orElse(0);

			log("laterCycleDeliveries=" + later.size()
					+ " laterCycleMaxFrames=" + best);
			Assert.assertFalse("No renders were delivered after the first cycle",
					later.isEmpty());
			Assert.assertTrue("Every render after the first cycle died almost"
							+ " immediately (max " + best + " frames) - mixdown"
							+ " state is accumulating across renders",
					best > 44100);
		} finally {
			process.destroy();
		}
	}
}
