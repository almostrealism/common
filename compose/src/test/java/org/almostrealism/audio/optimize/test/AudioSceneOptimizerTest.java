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

package org.almostrealism.audio.optimize.test;

import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.generative.NoOpGenerationProvider;
import org.almostrealism.audio.health.AudioHealthComputation;
import org.almostrealism.audio.health.StableDurationHealthComputation;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.optimize.AudioSceneOptimizer;
import org.almostrealism.audio.optimize.AudioScenePopulation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Genome;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.heredity.TemporalCellular;
import org.almostrealism.optimize.PopulationOptimizer;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class AudioSceneOptimizerTest implements CellFeatures, TestFeatures {
	protected AudioScene<?> scene() {
		// DesirablesProvider desirables = new DefaultDesirablesProvider<>(120, WesternScales.major(WesternChromatic.G3, 1));
		// return () -> new GeneticTemporalFactoryFromDesirables().from(desirables);
		return new AudioScene<>(null, 120, 2, 2, OutputLine.sampleRate, new ArrayList<>(), new NoOpGenerationProvider());
	}

	protected AudioSceneOptimizer optimizer() {
		int sources = 2;
		int delayLayers = 2;
		int cycles = 1;

		int params = 8;
		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		genomes.add(new ProjectedGenome(params));
		genomes.add(new ProjectedGenome(params));
		genomes.add(new ProjectedGenome(params));
		genomes.add(new ProjectedGenome(params));

		AudioScene<?> scene = scene();

		AudioSceneOptimizer optimizer = new AudioSceneOptimizer(scene,
				() -> AudioSceneOptimizer.defaultBreeder(0.001), null, cycles);

		optimizer.setChildrenFunction(g -> {
			System.out.println("Creating AudioScenePopulation...");
			AudioScenePopulation pop = new AudioScenePopulation(scene, genomes);
			AudioHealthComputation hc = (AudioHealthComputation) optimizer.getHealthComputation();
			pop.init(pop.getGenomes().get(0), hc.getOutput());
			return pop;
		});

		return optimizer;
	}

	@Test
	public void optimize() {
		PopulationOptimizer.enableVerbose = true;
		optimizer().run();
	}

	@Test
	public void healthTest() throws IOException {
		AudioScene<?> scene = scene();

		AtomicInteger index = new AtomicInteger();

		int params = 8;
		List<Genome<PackedCollection>> genomes = new ArrayList<>();
		genomes.add(new ProjectedGenome(params));
		genomes.add(new ProjectedGenome(params));
		genomes.add(new ProjectedGenome(params));
		genomes.add(new ProjectedGenome(params));

		AudioScenePopulation.store(genomes, new FileOutputStream(AudioSceneOptimizer.POPULATION_FILE));

		IntStream.range(0, 3).forEach(j ->
				dc(() -> {
					StableDurationHealthComputation health = new StableDurationHealthComputation(2, false);
					health.setMaxDuration(8);

					health.setOutputFile(() -> "results/layered-organ-optimizer-test-" + index.incrementAndGet() + ".wav");

					System.out.println("Creating LayeredOrganPopulation...");
					AudioScenePopulation pop =
							new AudioScenePopulation(null, AudioScenePopulation.read(new FileInputStream(AudioSceneOptimizer.POPULATION_FILE)));
					pop.init(pop.getGenomes().get(0), health.getOutput());

					IntStream.range(0, 4).forEach(i -> {
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
