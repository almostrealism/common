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
import org.almostrealism.spatial.ArrangementGenerationProcess;
import org.almostrealism.spatial.GenomicNetwork;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.generative.GenerationManager;
import org.almostrealism.studio.health.AudioHealthScore;
import org.almostrealism.studio.health.HealthComputationAdapter;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers how {@link ArrangementGenerationProcess} carries the best networks across
 * optimization cycles: the elite count it configures on the optimizer, and the way a
 * carried-over genome keeps its record and score when the records are rebuilt.
 *
 * <p>Kept separate from {@link ArrangementGenerationProcessTest} so these additions
 * run in their own forked JVM. Preparing a process constructs the health
 * computation's full-duration wave buffers, which are large direct allocations
 * freed only by the garbage collector; sharing a JVM with the rendering tests would
 * add that pressure to an already memory-heavy class. The one test here that
 * prepares a process first shrinks the standard duration so it allocates little, and
 * never renders.</p>
 */
public class ArrangementGenerationElitismTest extends TestSuiteBase {

	/**
	 * Builds a health score carrying the given output path. The path need not name an
	 * existing file: the record attaches the score and derives its key from the path
	 * without reading audio from disk.
	 *
	 * @param output the output file path the score reports
	 * @return a health score reporting that output
	 */
	private AudioHealthScore score(String output) {
		return new AudioHealthScore(44_100, 0.5, output, List.of(), List.of());
	}

	/** A check run against a process that has been prepared but never renders. */
	private interface PreparedCheck {
		/**
		 * Runs the check.
		 *
		 * @param process   the prepared process
		 * @param optimizer the optimizer the process prepared
		 */
		void run(ArrangementGenerationProcess process, AudioSceneOptimizer optimizer);
	}

	/**
	 * Prepares a process over a small scene, with the health computation's standard
	 * duration shrunk so preparation (which never renders here) allocates little, runs
	 * the given check against it, and restores the duration and destroys the process
	 * afterwards.
	 *
	 * @param networksPath the records file the process persists to
	 * @param check        the check to run against the prepared process
	 * @throws Exception if the process cannot be prepared
	 */
	private void withPreparedProcess(String networksPath, PreparedCheck check) throws Exception {
		new File("results").mkdirs();
		File networksFile = new File(networksPath);
		networksFile.delete();
		new File(AudioSceneOptimizer.POPULATION_FILE).delete();

		AudioScene<?> scene = new AudioScene<>(120.0, 2, 2, OutputLine.sampleRate);
		scene.getGenerationManager().setSettings(new GenerationManager.Settings());

		ArrangementGenerationProcess process =
				new ArrangementGenerationProcess(networksFile.getPath());

		// The health computation's wave buffers are sized to the standard duration;
		// shrink it so preparing this process (which never renders) allocates little.
		int standardDuration = HealthComputationAdapter.standardDurationSeconds;

		try {
			HealthComputationAdapter.setStandardDuration(2);
			process.prepare(scene, 1);
			check.run(process, process.getOptimizer());
		} finally {
			HealthComputationAdapter.setStandardDuration(standardDuration);
			process.destroy();
		}
	}

	/**
	 * A carried-over genome - the optimizer's elite, the same genome object in the
	 * next population - keeps the record it already had, and its score, when the
	 * records are rebuilt at the breeding-complete hook. Without re-delivering any
	 * score, the elite's record must still be its original object carrying its
	 * original score, while a genome new to the population starts with a fresh,
	 * unscored record. Driven through the optimizer's real breeding-complete hook,
	 * without rendering audio.
	 *
	 * @throws Exception if the process cannot be prepared
	 */
	@Test(timeout = 120_000)
	public void carriedOverGenomeKeepsItsRecordAndScore() throws Exception {
		withPreparedProcess("results/process-elite-records.xml", (process, optimizer) -> {
			Assert.assertEquals("prepare must apply the default elite count to the optimizer,"
							+ " so the best networks are carried across cycles",
					ArrangementGenerationProcess.DEFAULT_ELITE_COUNT, optimizer.getEliteCount());

			ProjectedGenome elite = new ProjectedGenome(PackedCollection.of(0.10, 0.20, 0.30));
			ProjectedGenome newcomer = new ProjectedGenome(PackedCollection.of(0.70, 0.80, 0.90));

			GenomicNetwork eliteRecord = new GenomicNetwork(0, elite);
			AudioHealthScore eliteScore = score("results/elite.wav");
			eliteRecord.setHealthScore(eliteScore);
			process.setNetworks(List.of(eliteRecord));

			// The elite genome object is carried unchanged into the next population.
			optimizer.setPopulation(new AudioScenePopulation(process.getLocalScene(),
					new ArrayList<>(List.of(elite, newcomer))));

			// Rebuilds the records for the new population.
			optimizer.breedingComplete();

			List<GenomicNetwork> records = process.getNetworks();
			Assert.assertEquals("Both population genomes must have a record",
					2, records.size());

			GenomicNetwork carried = records.stream()
					.filter(g -> g.getGenome() == elite)
					.findFirst().orElse(null);
			Assert.assertNotNull("The carried-over genome lost its record in the rebuild",
					carried);
			Assert.assertSame("The carried-over genome must keep its existing record object",
					eliteRecord, carried);
			Assert.assertSame("The carried-over record must keep its score without re-delivery",
					eliteScore, carried.getHealthScore());

			GenomicNetwork fresh = records.stream()
					.filter(g -> g.getGenome() == newcomer)
					.findFirst().orElse(null);
			Assert.assertNotNull("The newcomer must have a record", fresh);
			Assert.assertNull("The newcomer must start unscored", fresh.getHealthScore());
		});
	}

	/**
	 * Records are matched by genome signature rather than object identity, and each
	 * existing record is reused at most once. The population holds two distinct genome
	 * objects that both carry the signature of the one existing record (as happens
	 * when persistence round-trips a genome into a new object, and a bred child
	 * repeats it), placed after an unrelated newcomer. Exactly one of them must take
	 * over the existing record - with its score, its genome replaced by the population's
	 * object and its index moved to the genome's new position - while the other gets a
	 * fresh, unscored record of its own.
	 *
	 * @throws Exception if the process cannot be prepared
	 */
	@Test(timeout = 120_000)
	public void signatureMatchedRecordIsReusedAtMostOnce() throws Exception {
		withPreparedProcess("results/process-elite-signatures.xml", (process, optimizer) -> {
			ProjectedGenome original = new ProjectedGenome(PackedCollection.of(0.15, 0.25, 0.35));
			ProjectedGenome firstCopy = new ProjectedGenome(PackedCollection.of(0.15, 0.25, 0.35));
			ProjectedGenome secondCopy = new ProjectedGenome(PackedCollection.of(0.15, 0.25, 0.35));
			ProjectedGenome newcomer = new ProjectedGenome(PackedCollection.of(0.60, 0.50, 0.40));
			Assert.assertEquals("The copies must share the original's signature",
					original.signature(), firstCopy.signature());

			GenomicNetwork existingRecord = new GenomicNetwork(0, original);
			AudioHealthScore existingScore = score("results/signature.wav");
			existingRecord.setHealthScore(existingScore);
			process.setNetworks(List.of(existingRecord));

			optimizer.setPopulation(new AudioScenePopulation(process.getLocalScene(),
					new ArrayList<>(List.of(newcomer, firstCopy, secondCopy))));
			optimizer.breedingComplete();

			List<GenomicNetwork> records = process.getNetworks();
			Assert.assertEquals("Every population genome must have exactly one record",
					3, records.size());

			for (int i = 0; i < records.size(); i++) {
				Assert.assertEquals("Records must be indexed by population position",
						i, records.get(i).getIndex());
			}

			Assert.assertSame("The newcomer must lead the records, as it leads the population",
					newcomer, records.get(0).getGenome());
			Assert.assertNull("The newcomer must start unscored", records.get(0).getHealthScore());

			GenomicNetwork reused = records.get(1);
			Assert.assertSame("The first genome matching the signature must reuse the existing record",
					existingRecord, reused);
			Assert.assertSame("The reused record must hold the population's genome object",
					firstCopy, reused.getGenome());
			Assert.assertSame("The reused record must keep its score",
					existingScore, reused.getHealthScore());

			GenomicNetwork duplicate = records.get(2);
			Assert.assertNotSame("An existing record must not be reused twice",
					existingRecord, duplicate);
			Assert.assertSame("The second matching genome must get its own record",
					secondCopy, duplicate.getGenome());
			Assert.assertNull("The second matching genome's record must start unscored",
					duplicate.getHealthScore());
		});
	}

	/**
	 * The process defaults to a small positive elite count, accepts a reconfigured
	 * one, and rejects a negative one. This exercises the configuration surface
	 * without preparing a run, so it allocates nothing.
	 */
	@Test(timeout = 60_000)
	public void eliteCountConfiguration() {
		ArrangementGenerationProcess process =
				new ArrangementGenerationProcess("results/process-elite-config.xml");

		Assert.assertTrue("The default elite count must be positive",
				process.getEliteCount() > 0);
		Assert.assertEquals("The default elite count must match the documented default",
				ArrangementGenerationProcess.DEFAULT_ELITE_COUNT, process.getEliteCount());

		process.setEliteCount(3);
		Assert.assertEquals("A reconfigured elite count must be reported",
				3, process.getEliteCount());

		try {
			process.setEliteCount(-1);
			Assert.fail("A negative elite count must throw IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
