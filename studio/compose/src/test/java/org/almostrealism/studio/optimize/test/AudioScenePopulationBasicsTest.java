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
import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.studio.optimize.AudioScenePopulation.GenerationResult;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Behavioral tests for the hardware-independent surface of {@link AudioScenePopulation}: its
 * genome-list management, genome validation, the idempotence of its teardown methods, and the
 * {@link GenerationResult} value object.
 *
 * <p>The audio-rendering paths ({@code init}, {@code generate}, {@code enableGenome(int)}) require
 * a compiled realtime runner and are covered by the heavier tests in
 * {@code AudioScenePopulationTest} and {@code AudioScenePopulationLatchTest}; here the focus is the
 * population's bookkeeping, which must behave correctly before, between, and after those renders.</p>
 */
public class AudioScenePopulationBasicsTest extends TestSuiteBase {

	/**
	 * Builds a lightweight two-channel scene; no patterns or library are loaded, which is enough
	 * for genome structure and validation but not for rendering.
	 *
	 * @return a fresh scene
	 */
	private AudioScene<?> scene() {
		AudioScene<?> scene = new AudioScene<>(120.0, 2,
				AudioScene.DEFAULT_DELAY_LAYERS, OutputLine.sampleRate);
		scene.setTuning(new DefaultKeyboardTuning());
		return scene;
	}

	/** The constructor retains the scene, starts empty, and reports a size of zero. */
	@Test(timeout = 120000)
	public void newPopulationIsEmptyAndRetainsItsScene() {
		AudioScene<?> scene = scene();
		try {
			AudioScenePopulation pop = new AudioScenePopulation(scene);

			Assert.assertSame(scene, pop.getScene());
			Assert.assertEquals(0, pop.size());
			Assert.assertTrue(pop.getGenomes().isEmpty());
		} finally {
			scene.destroy();
		}
	}

	/** {@code setGenomes} replaces the list and {@code size} tracks it. */
	@Test(timeout = 120000)
	public void setGenomesReplacesTheListAndSizeFollows() {
		AudioScene<?> scene = scene();
		try {
			AudioScenePopulation pop = new AudioScenePopulation(scene);

			List<Genome<PackedCollection>> genomes = new ArrayList<>();
			genomes.add(new ProjectedGenome(PackedCollection.of(0.1, 0.2)));
			genomes.add(new ProjectedGenome(PackedCollection.of(0.3, 0.4)));

			pop.setGenomes(genomes);

			Assert.assertEquals(2, pop.size());
			Assert.assertSame(genomes, pop.getGenomes());
		} finally {
			scene.destroy();
		}
	}

	/** A {@code null} genome is never valid and is rejected without touching the scene. */
	@Test(timeout = 120000)
	public void nullGenomeIsNotValid() {
		AudioScene<?> scene = scene();
		try {
			AudioScenePopulation pop = new AudioScenePopulation(scene);
			Assert.assertFalse(pop.validateGenome(null));
		} finally {
			scene.destroy();
		}
	}

	/**
	 * A genome whose parameter layout does not match the scene cannot be applied, so validation
	 * reports it invalid and — the point of the method — swallows the failure rather than
	 * propagating it, leaving the population ready for the next genome.
	 */
	@Test(timeout = 120000)
	public void incompatibleGenomeIsInvalidButDoesNotThrow() {
		AudioScene<?> scene = scene();
		try {
			AudioScenePopulation pop = new AudioScenePopulation(scene);
			Genome<PackedCollection> incompatible = new ProjectedGenome(new PackedCollection(1));

			Assert.assertFalse(pop.validateGenome(incompatible));
			Assert.assertFalse("a repeated attempt must behave identically",
					pop.validateGenome(incompatible));
		} finally {
			scene.destroy();
		}
	}

	/**
	 * {@code disableGenome} and {@code destroy} must be safe before any runner has been built (the
	 * {@code temporal} field is still {@code null}), and {@code destroy} must be idempotent.
	 */
	@Test(timeout = 120000)
	public void teardownIsSafeWithoutARunner() {
		AudioScene<?> scene = scene();
		try {
			AudioScenePopulation pop = new AudioScenePopulation(scene);

			pop.disableGenome();
			pop.destroy();
			pop.destroy();

			Assert.assertEquals("teardown must leave the population's bookkeeping intact",
					0, pop.size());
		} finally {
			scene.destroy();
		}
	}

	/** {@link GenerationResult} reports exactly the path, genome, and timing it was built with. */
	@Test(timeout = 60000)
	public void generationResultCarriesItsFields() {
		Genome<PackedCollection> genome = new ProjectedGenome(PackedCollection.of(0.5, 0.6));
		GenerationResult result = new GenerationResult("out/genome-3.wav", genome, 1234L);

		Assert.assertEquals("out/genome-3.wav", result.getOutputPath());
		Assert.assertSame(genome, result.getGenome());
		Assert.assertEquals(1234L, result.getGenerationTime());
	}
}
