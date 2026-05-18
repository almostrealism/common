/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.studio.AudioScene;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.studio.health.HealthComputationAdapter;
import org.almostrealism.studio.health.MultiChannelAudioOutput;
import org.almostrealism.studio.health.SilenceDurationHealthComputation;
import org.almostrealism.studio.health.StableDurationHealthComputation;
import org.almostrealism.studio.optimize.AudioSceneOptimizer;
import org.almostrealism.studio.optimize.AudioScenePopulation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.util.TestDepth;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class StableDurationHealthComputationTest extends AudioScenePopulationTest {

	@BeforeClass
	public static void init() {
		StableDurationHealthComputation.enableVerbose = true;
	}

	@AfterClass
	public static void shutdown() {
		StableDurationHealthComputation.enableVerbose = false;
	}

	@Test(timeout = 600_000)
	@TestDepth(1)
	public void cellsPatternDataContext() {
		AtomicInteger index = new AtomicInteger();

		dc(() -> {
			StableDurationHealthComputation health = new StableDurationHealthComputation(2, false);
			health.setMaxDuration(8);
			health.setOutputFile(() -> "results/cells-pattern-dc-test" + index.incrementAndGet() + ".wav");

			TemporalCellular organ = randomOrgan(pattern(2, 2), health.getOutput(), health.getBatchSize());
			organ.reset();
			health.setTarget(organ);
			health.computeHealth();

			organ.reset();
			health.setTarget(organ);
			health.computeHealth();
		});
	}

	@Test(timeout = 300_000)
	@TestDepth(1)
	public void cellsPatternSmall() {
		int channels = 5;

		SilenceDurationHealthComputation.enableSilenceCheck = false;
		MixdownManager.enableMainFilterUp = false;
		MixdownManager.enableEfxFilters = false;

		HealthComputationAdapter.setStandardDuration(150);

		StableDurationHealthComputation health =
				new StableDurationHealthComputation(channels + 1); // extra channel for efx
		health.setOutputFile("results/cells-pattern-small.wav");

		AudioScene<?> pattern = pattern(channels, 2, true);
		pattern.assignGenome(pattern.getGenome().random());

		TemporalCellular organ = randomOrgan(pattern, health.getOutput(), health.getBatchSize());

		health.setTarget(organ);
		health.computeHealth();
	}

	@Test(timeout = 300_000)
	@TestDepth(2)
	public void cellsPatternLarge() {
		SilenceDurationHealthComputation.enableSilenceCheck = false;
		HealthComputationAdapter.setStandardDuration(150);

		StableDurationHealthComputation health = new StableDurationHealthComputation(5, false);
		health.setOutputFile("results/small-cells-pattern-test.wav");

		TemporalCellular cells = randomOrgan(pattern(5, 3), health.getOutput(), health.getBatchSize());

		cells.reset();
		health.setTarget(cells);
		health.computeHealth();
	}

	@Test(timeout = 900_000)
	@TestDepth(2)
	public void samplesPopulationHealth() throws IOException {
		AudioScene<?> scene = pattern(2, 2);

		AtomicInteger index = new AtomicInteger();

		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		genomes.add(scene.getGenome().random());
		genomes.add(scene.getGenome().random());

		AudioScenePopulation.store(genomes, new FileOutputStream(AudioSceneOptimizer.POPULATION_FILE));

		IntStream.range(0, 3).forEach(j ->
			dc(() -> {
				StableDurationHealthComputation health = new StableDurationHealthComputation(2, false);
				health.setMaxDuration(8);

				health.setOutputFile(() -> "results/samples-pop-test-" + index.incrementAndGet() + ".wav");

				log("Creating AudioScenePopulation...");
				AudioScenePopulation pop =
						new AudioScenePopulation(null, AudioScenePopulation.read(new FileInputStream(AudioSceneOptimizer.POPULATION_FILE)));
				pop.init(pop.getGenomes().get(0), health.getOutput());

				IntStream.range(0, 2).forEach(i -> {
					TemporalCellular organ = pop.enableGenome(i);

					try {
						health.setTarget(organ);
						health.computeHealth();
					} finally {
						health.reset();
						pop.disableGenome();
					}
				});

				return null;
			}));
	}
}
