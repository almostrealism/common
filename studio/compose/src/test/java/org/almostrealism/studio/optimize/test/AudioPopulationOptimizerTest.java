/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio.optimize.test;

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.optimize.HealthComputation;
import org.almostrealism.optimize.Population;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioPopulationOptimizer;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Behavioral tests for {@link AudioPopulationOptimizer}'s hardware-independent surface: the default
 * health-computation factory and the population-persistence path that reads the JSON file at the
 * start of a cycle.
 *
 * <p>The full {@code run()} / {@code iterate()} loop renders audio and evaluates health, so it is
 * not exercised here; what is pinned is the behavior on either side of a render — how the optimizer
 * sources its health computation and what it does when the persisted population file is absent.</p>
 */
public class AudioPopulationOptimizerTest extends TestSuiteBase {

	/**
	 * Builds a lightweight two-channel scene used only to back the {@link AudioScenePopulation} the
	 * children function returns; no rendering is performed.
	 *
	 * @return a fresh scene
	 */
	private AudioScene<?> scene() {
		AudioScene<?> scene = new AudioScene<>(120.0, 2,
				AudioScene.DEFAULT_DELAY_LAYERS, OutputLine.sampleRate);
		scene.setTuning(new DefaultKeyboardTuning());
		return scene;
	}

	/**
	 * The default health computation used by the stem-count constructor is a
	 * {@link StableDurationHealthComputation}; this is the metric the optimizer scores every genome
	 * against, so the factory's choice of type is part of its contract.
	 */
	@Test(timeout = 120000)
	public void healthComputationFactoryReturnsStableDurationComputation() {
		HealthComputation<TemporalCellular, ?> health =
				AudioPopulationOptimizer.healthComputation(2);

		Assert.assertTrue("default health computation must be a StableDurationHealthComputation",
				health instanceof StableDurationHealthComputation);

		((StableDurationHealthComputation) health).destroy();
	}

	/**
	 * When the persistence file does not exist, {@code readPopulation} must not fail: it falls back
	 * to an empty genome list, applies the children function to it, and persists the (empty)
	 * population — so after the call the population is empty and the file has been created.
	 */
	@Test(timeout = 120000)
	public void readPopulationWithMissingFileYieldsEmptyPopulation() throws Exception {
		AudioScene<?> scene = scene();
		File file = File.createTempFile("population-test", ".json");
		Assert.assertTrue("precondition: start from a missing file", file.delete());

		Function<List<Genome<PackedCollection>>, Population> children =
				loaded -> new AudioScenePopulation(scene, new ArrayList<>(loaded));

		AudioPopulationOptimizer<TemporalCellular> optimizer =
				new AudioPopulationOptimizer<>(2, children, null, null,
						file.getAbsolutePath(), 1);

		try {
			optimizer.readPopulation();

			Assert.assertEquals("a missing file yields an empty population",
					0, optimizer.getPopulation().size());
			Assert.assertTrue("readPopulation must persist the population it loaded",
					file.exists());
		} finally {
			optimizer.destroy();
			scene.destroy();
			file.delete();
		}
	}

	/**
	 * A persistence file holding two genomes is read back into a population of those two genomes,
	 * each carrying its original parameters — the round trip that lets an optimization run resume
	 * from where a previous run left off.
	 */
	@Test(timeout = 120000)
	public void readPopulationLoadsPersistedGenomes() throws Exception {
		AudioScene<?> scene = scene();
		File file = File.createTempFile("population-test", ".json");

		List<Genome<PackedCollection>> saved = new ArrayList<>();
		saved.add(new ProjectedGenome(PackedCollection.of(0.1, 0.2, 0.3)));
		saved.add(new ProjectedGenome(PackedCollection.of(0.9, -0.5)));
		AudioScenePopulation.store(saved, new FileOutputStream(file));

		Function<List<Genome<PackedCollection>>, Population> children =
				loaded -> new AudioScenePopulation(scene, new ArrayList<>(loaded));

		AudioPopulationOptimizer<TemporalCellular> optimizer =
				new AudioPopulationOptimizer<>(2, children, null, null,
						file.getAbsolutePath(), 1);

		try {
			optimizer.readPopulation();

			Assert.assertEquals(2, optimizer.getPopulation().size());

			List<Genome<PackedCollection>> loaded = optimizer.getPopulation().getGenomes();
			Assert.assertArrayEquals(new double[] { 0.1, 0.2, 0.3 },
					((ProjectedGenome) loaded.get(0)).getParameters().toArray(), 1.0e-9);
			Assert.assertArrayEquals(new double[] { 0.9, -0.5 },
					((ProjectedGenome) loaded.get(1)).getParameters().toArray(), 1.0e-9);
		} finally {
			optimizer.destroy();
			scene.destroy();
			file.delete();
		}
	}
}
