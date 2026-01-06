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

package org.almostrealism.audio.filter.test;

import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.Cells;
import org.almostrealism.audio.generative.NoOpGenerationProvider;
import org.almostrealism.audio.health.StableDurationHealthComputation;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.hardware.mem.MemoryBankAdapter.CacheLevel;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.util.TestFeatures;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;

public class PeriodicCellAdjustmentTest implements TestFeatures {
	@BeforeClass
	public static void init() {
		// AcceleratedTimeSeries.defaultCacheLevel = CacheLevel.ALL;
		StableDurationHealthComputation.enableVerbose = true;
	}

	@AfterClass
	public static void shutdown() {
		AcceleratedTimeSeries.defaultCacheLevel = CacheLevel.NONE;
		StableDurationHealthComputation.enableVerbose = false;
	}

	protected AudioScene<?> scene() {
//		DefaultDesirablesProvider<WesternChromatic> provider = new DefaultDesirablesProvider<>(120, WesternScales.major(WesternChromatic.G3, 1));
//		provider.getSamples().add(new File("src/main/resources/health-test-in.wav"));
//		return new GeneticTemporalFactoryFromDesirables().from(provider);
		return new AudioScene<>(null, 120, 2, 2, OutputLine.sampleRate, new ArrayList<>(), new NoOpGenerationProvider());
	}

	@Test
	public void healthTestNoAdjustment() {
		if (testDepth < 1) return;

		StableDurationHealthComputation health = new StableDurationHealthComputation(2, false);
		health.setMaxDuration(8);
		health.setOutputFile("results/periodic-test-noadjust.wav");

		Cells organ = scene().getCells(health.getOutput());
		organ.reset();
		health.setTarget(organ);
		health.computeHealth();
	}

	@Test
	public void healthTestWithAdjustment() {
		if (testDepth < 1) return;

		StableDurationHealthComputation health = new StableDurationHealthComputation(2, false);
		health.setMaxDuration(8);
		health.setOutputFile("results/periodic-test-adjust.wav");

		Cells organ = scene().getCells(health.getOutput());
		organ.reset();
		health.setTarget(organ);
		health.computeHealth();
	}
}
