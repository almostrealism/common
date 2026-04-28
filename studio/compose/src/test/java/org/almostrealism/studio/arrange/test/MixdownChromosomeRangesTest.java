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

package org.almostrealism.studio.arrange.test;

import io.almostrealism.relation.Factor;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Chromosome;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.ProjectedGenome;
import org.almostrealism.studio.arrange.AutomationManager;
import org.almostrealism.studio.arrange.GlobalTimeManager;
import org.almostrealism.studio.arrange.MixdownManager;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies that the {@code minTransmission/maxTransmission} and
 * {@code minWetOut/maxWetOut} ranges configured by
 * {@link MixdownManager.Configuration} are actually reflected in the runtime
 * values of the {@code transmission} and {@code wetOut} chromosomes after a
 * genome is assigned.
 *
 * <p>Existence motivated by an EFX-bus divergence on the
 * {@code feature/skytnt-midi-model} branch where the {@code MixdownManager}
 * EFX delay grid produced output peaks &gt;1000x unity even with the
 * configured caps tightened to a stable region. The
 * {@link org.almostrealism.audio.test.MselfFeedbackMatrixTest} confirms that
 * {@code mself} itself is well-behaved with bounded gain values, so the
 * remaining suspect is that the chromosome cap mechanism isn't actually
 * reaching the runtime gene values. This test pins that down: we read the
 * actual evaluated factor values for {@code transmission} and {@code wetOut}
 * after {@link ProjectedGenome#assignTo(PackedCollection)} runs, and assert
 * each value falls within the range we configured.</p>
 */
public class MixdownChromosomeRangesTest extends TestSuiteBase implements CellFeatures {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;
	private static final int CHANNELS = 6;
	private static final int DELAY_LAYERS = 4;
	private static final int GENOME_PARAMS = 256;

	@Test(timeout = 30_000)
	public void transmissionAndWetOutValuesRespectConfiguredRange() {
		double measureDuration = Frequency.forBPM(120).l(4);
		GlobalTimeManager time = new GlobalTimeManager(
				measure -> (int) (measure * measureDuration * SAMPLE_RATE));

		ProjectedGenome genome = new ProjectedGenome(GENOME_PARAMS);
		AutomationManager automation = new AutomationManager(
				genome.addChromosome(), time.getClock(),
				() -> measureDuration, SAMPLE_RATE);
		MixdownManager mixdown = new MixdownManager(genome.addChromosome(),
				CHANNELS, DELAY_LAYERS, automation, time.getClock(), SAMPLE_RATE);

		// Mirror AudioScene: consolidate, then assign a randomized parameter
		// vector and refresh.
		genome.consolidateGeneValues();
		PackedCollection params = new PackedCollection(GENOME_PARAMS);
		params.randFill();
		genome.assignTo(params);

		// Read the configured bounds directly so the test tracks the
		// Configuration constructor rather than pinning a branch-specific
		// numeric region.
		MixdownManager.Configuration config = new MixdownManager.Configuration(CHANNELS);
		double tMin = config.minTransmission, tMax = config.maxTransmission;
		double wMin = config.minWetOut, wMax = config.maxWetOut;

		Chromosome<PackedCollection> transmission = readChromosomeField(mixdown, "transmission");
		Chromosome<PackedCollection> wetOut = readChromosomeField(mixdown, "wetOut");

		log("transmission chromosome length=" + transmission.length());
		log("wetOut chromosome length=" + wetOut.length());

		boolean failed = false;

		StringBuilder tDump = new StringBuilder("transmission values:\n");
		for (int g = 0; g < transmission.length(); g++) {
			Gene<PackedCollection> gene = transmission.valueAt(g);
			tDump.append("  gene[").append(g).append("] = [");
			for (int i = 0; i < gene.length(); i++) {
				double v = evaluateFactor(gene.valueAt(i));
				if (i > 0) tDump.append(", ");
				tDump.append(String.format("%.4f", v));
				if (v < tMin || v > tMax) failed = true;
			}
			tDump.append("]\n");
		}
		log(tDump.toString());

		StringBuilder wDump = new StringBuilder("wetOut values:\n");
		for (int g = 0; g < wetOut.length(); g++) {
			Gene<PackedCollection> gene = wetOut.valueAt(g);
			wDump.append("  gene[").append(g).append("] = [");
			for (int i = 0; i < gene.length(); i++) {
				double v = evaluateFactor(gene.valueAt(i));
				if (i > 0) wDump.append(", ");
				wDump.append(String.format("%.4f", v));
				if (v < wMin || v > wMax) failed = true;
			}
			wDump.append("]\n");
		}
		log(wDump.toString());

		assertTrue("Some transmission/wetOut values fell outside the configured range. "
				+ "See logs above for the dump. Configured: transmission in ["
				+ tMin + ", " + tMax + "], wetOut in [" + wMin + ", " + wMax + "].",
				!failed);
	}

	private double evaluateFactor(Factor<PackedCollection> factor) {
		return factor.getResultant(c(1.0)).get().evaluate().toDouble(0);
	}

	@SuppressWarnings("unchecked")
	private Chromosome<PackedCollection> readChromosomeField(MixdownManager mixdown, String name) {
		try {
			Field f = MixdownManager.class.getDeclaredField(name);
			f.setAccessible(true);
			return (Chromosome<PackedCollection>) f.get(mixdown);
		} catch (Exception e) {
			fail("Reflection failed for field '" + name + "': " + e);
			return null;
		}
	}
}
