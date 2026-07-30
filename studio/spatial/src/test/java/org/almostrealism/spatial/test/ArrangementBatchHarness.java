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

import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.spatial.ArrangementGenerationProcess;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;

import java.io.File;

/**
 * Headless entry point that generates a full listening batch: one optimization
 * cycle over a population of the configured size, producing a rendered WAV and
 * a full set of stem files for every scored genome, with a summary of every
 * delivery printed at the end. Run from the module directory with the test
 * classpath; outputs land in the health directory under the working directory.
 */
public class ArrangementBatchHarness implements ConsoleFeatures {

	/** Genomes per batch. */
	private static final int POPULATION = 16;

	/** Evaluation seconds per genome; long enough to judge by ear. */
	private static final int MAX_DURATION_SECONDS = 90;

	/**
	 * Runs the batch and logs a per-genome summary.
	 *
	 * @param args unused
	 * @throws Exception if the scene cannot be loaded or the run fails
	 */
	public static void main(String[] args) throws Exception {
		new ArrangementBatchHarness().run();
	}

	/**
	 * Generates the batch: one optimization cycle over the configured population,
	 * logging each delivered score and a final summary of every output and stem.
	 *
	 * @throws Exception if the scene cannot be loaded or the run fails
	 */
	public void run() throws Exception {
		if (!CuratedArrangementFixture.curatedSceneAvailable()) {
			log("No curated library available");
			return;
		}

		CuratedArrangementFixture.enableRealtimeMixdown();
		PopulationOptimizer.popSize = POPULATION;
		PopulationOptimizer.maxChildren = POPULATION;

		new File("results").mkdirs();
		File networksFile = new File("results/batch-networks.xml");
		networksFile.delete();
		new File(AudioSceneOptimizer.POPULATION_FILE).delete();

		AudioScene<?> scene = CuratedArrangementFixture.loadCuratedScene(64);
		ArrangementGenerationProcess process =
				new ArrangementGenerationProcess(networksFile.getPath());

		StringBuilder summary = new StringBuilder();
		int[] count = {0};

		process.setScoreListener((network, score) -> {
			count[0]++;
			summary.append("genome ").append(count[0])
					.append(" attached=").append(network != null)
					.append(" seconds=").append(score.getDuration())
					.append(" score=").append(score.getScore())
					.append(" output=").append(score.getOutput())
					.append(" stems=").append(score.getStems())
					.append(System.lineSeparator());
			log("batchDelivery=" + count[0]
					+ " seconds=" + score.getDuration()
					+ " score=" + score.getScore());
		});
		process.setErrorListener(e -> {
			summary.append("error ").append(e).append(System.lineSeparator());
			warn("batchError " + e.getClass().getSimpleName(), e);
		});

		try {
			process.prepare(scene, 1);
			((StableDurationHealthComputation) process.getOptimizer()
					.getHealthComputation()).setMaxDuration(MAX_DURATION_SECONDS);
			process.run();
		} finally {
			process.destroy();
		}

		log("batchComplete deliveries=" + count[0]);
		log(summary.toString());
	}
}
