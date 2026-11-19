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

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.spatial.ArrangementGenerationProcess;
import org.almostrealism.spatial.GenomicNetwork;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.generative.GenerationManager;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.studio.optimize.AudioScenePopulation;
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
	 * Requires the curated sample library and pattern factory the curated scene is
	 * loaded from, via {@link #requireResourceDirectory(String, String, File)} and
	 * {@link #requireResource(String, String, boolean)}: a miss fails on any GPU host
	 * or host that declares the location, and skips only on a CPU-only host that did
	 * not; an empty library directory fails on every host.
	 */
	private void requireCuratedScene() {
		requireResourceDirectory("curated sample library", "AR_RINGS_LIBRARY",
				new File(CuratedArrangementFixture.SAMPLES));
		requireResource("curated pattern factory " + new File(CuratedArrangementFixture.PATTERN_FACTORY).getAbsolutePath(),
				"AR_RINGS_PATTERNS", new File(CuratedArrangementFixture.PATTERN_FACTORY).isFile());
	}

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
		requireCuratedScene();

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
		requireCuratedScene();

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

	/**
	 * Builds a health score carrying the given output path. The path need not name
	 * an existing file: the record attaches the score and derives its key from the
	 * path without reading audio from disk.
	 *
	 * @param output the output file path the score reports
	 * @return a health score reporting that output
	 */
	private AudioHealthScore score(String output) {
		return new AudioHealthScore(44_100, 0.5, output, List.of(), List.of());
	}

	/**
	 * Reproduces the score loss that occurred when records were rebuilt lazily on the
	 * first unmatched signature rather than once per cycle: a cycle's population
	 * {@code [A', C]} where {@code A'} shares the previous population's {@code A}
	 * signature, with scores delivered {@code A'} then {@code C}. Driven through the
	 * optimizer's real breeding-complete and health-delivery hooks, without rendering
	 * audio, so the record-synchronization behavior is exercised in isolation.
	 *
	 * <p>Before the fix the {@code A'} score attached to the stale {@code A} record and
	 * the later {@code C} signature triggered a rebuild that discarded it, leaving the
	 * {@code A'} record unscored. After the fix the records are rebuilt once, at the
	 * breeding-complete hook, so both delivered scores survive the cycle.</p>
	 *
	 * @throws Exception if the process cannot be prepared
	 */
	@Test(timeout = 120_000)
	public void scoresSurviveWhenABredChildRepeatsAPriorSignature() throws Exception {
		new File("results").mkdirs();
		File networksFile = new File("results/process-cycle-records.xml");
		networksFile.delete();
		new File(AudioSceneOptimizer.POPULATION_FILE).delete();

		AudioScene<?> scene = new AudioScene<>(120.0, 2, 2, OutputLine.sampleRate);
		// A bare scene's generator list is uninitialized; prepare() clones the scene,
		// so give it an empty generator set to snapshot.
		scene.getGenerationManager().setSettings(new GenerationManager.Settings());

		ArrangementGenerationProcess process =
				new ArrangementGenerationProcess(networksFile.getPath());

		try {
			process.prepare(scene, 1);
			AudioSceneOptimizer optimizer = process.getOptimizer();

			// A and A' share parameters, hence a signature; B and C differ from both.
			ProjectedGenome genomeA = new ProjectedGenome(PackedCollection.of(0.10, 0.20, 0.30));
			ProjectedGenome genomeB = new ProjectedGenome(PackedCollection.of(0.40, 0.50, 0.60));
			ProjectedGenome genomeAPrime = new ProjectedGenome(PackedCollection.of(0.10, 0.20, 0.30));
			ProjectedGenome genomeC = new ProjectedGenome(PackedCollection.of(0.70, 0.80, 0.90));

			String signatureA = genomeA.signature();
			String signatureC = genomeC.signature();
			Assert.assertEquals("A and A' must share a signature",
					signatureA, genomeAPrime.signature());
			Assert.assertNotEquals("C must not share A's signature",
					signatureA, signatureC);

			GenomicNetwork recordA = new GenomicNetwork(0, genomeA);
			recordA.setHealthScore(score("results/prev-A.wav"));
			GenomicNetwork recordB = new GenomicNetwork(1, genomeB);
			recordB.setHealthScore(score("results/prev-B.wav"));
			process.setNetworks(List.of(recordA, recordB));

			optimizer.setPopulation(new AudioScenePopulation(process.getLocalScene(),
					new ArrayList<>(List.of(genomeAPrime, genomeC))));

			// Breeding has produced the population; the records sync to it once here.
			optimizer.breedingComplete();

			// Scores arrive A' then C, through the optimizer's own delivery path.
			optimizer.getHealthListener().accept(signatureA, score("results/cycle-A.wav"));
			optimizer.getHealthListener().accept(signatureC, score("results/cycle-C.wav"));

			List<GenomicNetwork> records = process.getNetworks();
			Assert.assertEquals("The cycle's population has two records",
					2, records.size());

			for (GenomicNetwork record : records) {
				Assert.assertNotNull("Record for signature "
						+ record.getGenome().signature()
						+ " carries no score after the cycle", record.getHealthScore());
			}

			GenomicNetwork aPrimeRecord = records.stream()
					.filter(g -> signatureA.equals(g.getGenome().signature()))
					.findFirst().orElse(null);
			Assert.assertNotNull("No record carries A's signature", aPrimeRecord);
			Assert.assertNotNull("The score delivered before the later signature"
					+ " was lost when the records were rebuilt",
					aPrimeRecord.getHealthScore());
		} finally {
			process.destroy();
		}
	}
}
