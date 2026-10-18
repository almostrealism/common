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

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that a genome whose activation fails does not leave
 * {@link AudioScenePopulation} with its active-genome latch stuck.
 *
 * <p>The latch was previously recorded before the scene accepted the genome, so
 * one incompatible genome (or any failure inside the scene's parameter refresh)
 * caused every later {@code enableGenome} in an optimizer batch to throw the
 * already-active {@link IllegalStateException} — an entire "Run N Cycle(s)"
 * batch producing nothing but latch errors after a single real failure. The
 * latch must only be set once activation succeeds, so each genome's failure
 * surfaces its own cause and the next genome gets a clean attempt.</p>
 */
public class AudioScenePopulationLatchTest extends TestSuiteBase {

	/**
	 * Activates an incompatible genome (whose parameter assignment fails), then
	 * confirms the population still accepts a compatible genome afterwards.
	 */
	@Test(timeout = 120000)
	public void failedGenomeDoesNotStickTheLatch() {
		AudioScene<?> scene = new AudioScene<>(120.0, 2,
				AudioScene.DEFAULT_DELAY_LAYERS, OutputLine.sampleRate);
		scene.setTuning(new DefaultKeyboardTuning());

		Genome<PackedCollection> compatible = scene.getGenome();
		Genome<PackedCollection> incompatible =
				new ProjectedGenome(new PackedCollection(1));

		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		genomes.add(compatible);

		AudioScenePopulation pop = new AudioScenePopulation(scene, genomes);
		StableDurationHealthComputation health =
				new StableDurationHealthComputation(2, true);

		try {
			try {
				pop.init(incompatible, health.getOutput(), null, health.getBatchSize());
				Assert.fail("Initializing with an incompatible genome must fail");
			} catch (IllegalArgumentException e) {
				log("Incompatible genome rejected as expected");
			}

			Assert.assertTrue(
					"A compatible genome must be accepted after a failed activation",
					pop.validateGenome(compatible));

			try {
				pop.init(incompatible, health.getOutput(), null, health.getBatchSize());
				Assert.fail("A repeated incompatible genome must fail the same way");
			} catch (IllegalArgumentException e) {
				log("Incompatible genome rejected again, not masked by a stuck latch");
			}

			Assert.assertTrue(
					"Validation must keep succeeding after repeated failures",
					pop.validateGenome(compatible));
		} finally {
			pop.destroy();
			health.destroy();
			scene.destroy();
		}
	}

	/**
	 * Verifies that {@link AudioScenePopulation#store(List, java.io.OutputStream)} and
	 * {@link AudioScenePopulation#read(java.io.InputStream)} round-trip a population's
	 * genomes through their {@link ProjectedGenome} parameter arrays without loss.
	 */
	@Test(timeout = 120000)
	public void storeReadRoundTripsGenomeParameters() throws IOException {
		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		genomes.add(new ProjectedGenome(PackedCollection.of(0.25, -1.5, 3.0)));
		genomes.add(new ProjectedGenome(PackedCollection.of(7.0, 0.0, -0.125)));

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		AudioScenePopulation.store(genomes, buffer);

		List<Genome<PackedCollection>> restored =
				AudioScenePopulation.read(new ByteArrayInputStream(buffer.toByteArray()));

		Assert.assertEquals(genomes.size(), restored.size());

		for (int i = 0; i < genomes.size(); i++) {
			double[] expected = ((ProjectedGenome) genomes.get(i)).getParameters().toArray();
			double[] actual = ((ProjectedGenome) restored.get(i)).getParameters().toArray();
			Assert.assertArrayEquals("Genome " + i + " parameters must survive serialization",
					expected, actual, 0.0);
		}
	}
}
