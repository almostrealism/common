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

package org.almostrealism.bioscience.connectome.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.almostrealism.bioscience.connectome.CellTypeConnectome;
import org.almostrealism.graph.RatePopulation;
import org.almostrealism.time.SimulationRunner;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * Runs the rate model's sugar experiment on the committed MaleCNS cell-type sugar circuit, the
 * scaled-down counterpart of {@link CellTypeRateModelTest} that every CI lane can afford.
 *
 * <p>The fixture under {@code src/test/resources/connectome/cell-type-sugar-circuit} was cut out
 * of the full 11,751-type matrix by {@code male_cns_cell_types.py circuit}: 250 types (the sugar
 * GRNs {@code LB3a}–{@code LB3d}, the matched control the full-matrix test draws, MN9, and the
 * active types whose activity reaches MN9 most strongly), the exact sub-block of the full matrix
 * with the full-matrix type sizes. Its {@code rate-reference.json} holds the rate every kept type
 * reaches in each run of the experiment on this sub-matrix, computed in FP64 by the script, and
 * the full-matrix MN9 values the cut is traced to (the full matrix activates about 9,500 types,
 * so the cut is not exact; the README states its deviation).</p>
 *
 * <h2>Precision allowance</h2>
 * <p>Every rate is compared with the FP64 expectation within {@link #RATE_ALLOWANCE}. Measured on
 * a Mac Studio (Apple silicon), the largest difference over all kept types and both driven runs
 * was 1.1e-13 on native FP64 and 1.24e-4 in FP32, on native and on Metal alike. The class runs
 * in a few seconds.</p>
 */
public class CellTypeSugarCircuitTest extends CellTypeRateChecks {
	/** The fraction of the recorded GRN-over-control margin MN9 must keep on the platform. */
	private static final double MARGIN_FRACTION = 0.9;

	/**
	 * The circuit loads through {@link CellTypeConnectome#load} and every entry the script
	 * recomputed from the raw MaleCNS edge table equals the loaded weight exactly.
	 */
	@Test(timeout = 300000)
	public void spotChecks() throws IOException {
		log("spotChecks=" + CellTypeConnectomeTest.assertSpotChecks(sugarCircuit(), 90));
	}

	/**
	 * With no drive every rate stays exactly zero; driving the sugar GRNs, and then the matched
	 * control, for 1,000 steps from zero rates reaches the recorded rate of every kept type,
	 * MN9's included, within {@link #RATE_ALLOWANCE}; and MN9's GRN rise exceeds its control rise
	 * by at least {@value #MARGIN_FRACTION} of the recorded margin.
	 *
	 * <p>The run length is {@link #STEPS}, not the fixture's. The driven sets and constants are
	 * read from the fixture but must be the experiment the tests define (see
	 * {@link #assertDrivenSets} and {@link #assertConstants}), so a regenerated fixture cannot
	 * drive other types or run other dynamics and still agree with its own recorded rates.</p>
	 */
	@Test(timeout = 300000)
	public void sugarDrivesMn9() throws IOException {
		File directory = sugarCircuit();
		JsonObject reference = NeuronConnectomeChecks.json(directory, SUGAR_CIRCUIT_REFERENCE_FILE).getAsJsonObject();
		JsonObject driven = reference.getAsJsonObject("driven");
		JsonObject expectedFinal = reference.getAsJsonObject("final");
		int steps = STEPS;

		try (CellTypeConnectome connectome = CellTypeConnectome.load(directory);
			 RatePopulation population = population(connectome)) {
			int mn9 = connectome.indexOf(MN9);
			assertConstants(reference.getAsJsonObject("constants"));
			assertDrivenSets(connectome, driven);
			SimulationRunner runner = new SimulationRunner(population, DT);

			double[] baseline = responseRates(runner, population, new int[0], steps);
			assertEquals(0.0, maxDifference(new double[baseline.length], baseline), 0.0);

			double[] mn9Final = new double[2];
			String[] runs = { "grn", "control" };
			for (int r = 0; r < runs.length; r++) {
				double[] expected = doubles(expectedFinal.getAsJsonArray(runs[r]));
				double[] actual = responseRates(runner, population,
						indices(connectome, names(driven.getAsJsonArray(runs[r]))), steps);
				double difference = maxDifference(expected, actual);
				log("backend=" + backend() + " run=" + runs[r] + " mn9Expected=" + expected[mn9]
						+ " mn9Actual=" + actual[mn9] + " maxDifference=" + difference);
				assertEquals(runs[r], expected[mn9], actual[mn9], RATE_ALLOWANCE);
				assertTrue(runs[r] + " differs by " + difference, difference <= RATE_ALLOWANCE);
				mn9Final[r] = actual[mn9];
			}

			runner.destroy();

			double margin = reference.getAsJsonObject("cut").get("margin").getAsDouble();
			assertTrue("recorded margin is not positive", margin > 0.0);
			assertTrue("GRN drive raises MN9 by " + mn9Final[0] + " against " + mn9Final[1] + " for control",
					mn9Final[0] - mn9Final[1] >= MARGIN_FRACTION * margin);
		}
	}

	/**
	 * {@link #assertDrivenSets} accepts the driven sets the fixture records and rejects every
	 * departure from the experiment: a driven baseline, a GRN run without all of
	 * {@link #SUGAR_GRNS}, and a control that is short, gustatory, not sensory, repeated,
	 * outside 20% of its GRN type's outgoing synapse count, or valid under that rule but not the
	 * seeded draw {@link #MATCHED_CONTROL}. Each rejected control breaks one part
	 * of the rule and satisfies the others, so every part is shown to be checked.
	 */
	@Test(timeout = 300000)
	public void drivenSetsAreTheExperiment() throws IOException {
		File directory = sugarCircuit();
		JsonObject driven = NeuronConnectomeChecks.json(directory, SUGAR_CIRCUIT_REFERENCE_FILE)
				.getAsJsonObject().getAsJsonObject("driven");

		try (CellTypeConnectome connectome = CellTypeConnectome.load(directory)) {
			assertDrivenSets(connectome, driven);

			List<String> control = names(driven.getAsJsonArray("control"));
			assertDrivenSetsRejected(connectome, driven, "baseline", List.of(MN9));
			assertDrivenSetsRejected(connectome, driven, "grn", SUGAR_GRNS.subList(0, 3));
			assertDrivenSetsRejected(connectome, driven, "control", control.subList(0, 3));

			// A GRN type is sensory and matches its own count exactly, but is gustatory.
			assertDrivenSetsRejected(connectome, driven, "control",
					List.of(SUGAR_GRNS.get(0), control.get(1), control.get(2), control.get(3)));

			// A type of matching count that is not sensory.
			int grn = connectome.indexOf(SUGAR_GRNS.get(0));
			double[] out = connectome.getOutSynapses().toArray();
			int unrelated = IntStream.range(0, connectome.getTypeCount())
					.filter(i -> !connectome.getSuperclass(i).contains("sensory"))
					.filter(i -> Math.abs(out[i] - out[grn]) <= 0.2 * out[grn])
					.findFirst().orElseThrow(() -> new AssertionError("no non-sensory type of matching count"));
			assertDrivenSetsRejected(connectome, driven, "control",
					List.of(connectome.getTypeNames().get(unrelated), control.get(1), control.get(2), control.get(3)));

			// The first control type is also a candidate for the second GRN type, but is repeated.
			assertTrue(IntStream.of(controlCandidates(connectome, connectome.indexOf(SUGAR_GRNS.get(1))))
					.anyMatch(i -> i == connectome.indexOf(control.get(0))));
			assertDrivenSetsRejected(connectome, driven, "control",
					List.of(control.get(0), control.get(0), control.get(2), control.get(3)));

			// Sensory, non-gustatory and distinct, but matched to the wrong GRN types' counts.
			assertDrivenSetsRejected(connectome, driven, "control",
					List.of(control.get(2), control.get(1), control.get(0), control.get(3)));

			// The first two control types swapped: each is a candidate for its new GRN type, none is
			// repeated, but the set is not the seeded draw.
			List<String> swapped = List.of(control.get(1), control.get(0), control.get(2), control.get(3));
			for (int i = 0; i < swapped.size(); i++) {
				int type = connectome.indexOf(swapped.get(i));
				assertTrue(swapped.get(i) + " is not a candidate for " + SUGAR_GRNS.get(i),
						IntStream.of(controlCandidates(connectome, connectome.indexOf(SUGAR_GRNS.get(i))))
								.anyMatch(candidate -> candidate == type));
			}

			assertDrivenSetsRejected(connectome, driven, "control", swapped);
		}
	}

	/**
	 * {@link #assertConstants} accepts the constants the fixture records and rejects a change to
	 * any one of them, a shorter run among them, so the fixture cannot set the experiment's
	 * workload or dynamics.
	 */
	@Test(timeout = 60000)
	public void constantsAreTheExperiment() throws IOException {
		JsonObject constants = NeuronConnectomeChecks.json(sugarCircuit(), SUGAR_CIRCUIT_REFERENCE_FILE)
				.getAsJsonObject().getAsJsonObject("constants");
		assertConstants(constants);

		for (String name : List.of("dt", "tau", "r_max", "gain", "drive", "steps")) {
			JsonObject altered = constants.deepCopy();
			altered.addProperty(name, name.equals("steps") ? STEPS / 10 : constants.get(name).getAsDouble() * 2);

			try {
				assertConstants(altered);
			} catch (AssertionError expected) {
				continue;
			}

			Assert.fail("Changing " + name + " to " + altered.get(name) + " was accepted");
		}
	}

	/**
	 * The first draws of {@code new java.util.Random(CONTROL_SEED).nextInt(bound)} are the values
	 * {@code test_male_cns_cell_types.py} requires of the script's port of that generator, for
	 * the same bounds. Both sides are held to one recorded sequence, so the control the script
	 * draws for the rate reference and this circuit stays the one the full-matrix test draws.
	 */
	@Test(timeout = 60000)
	public void controlDrawMatchesScriptPort() {
		int[] bounds = { 7, 16, 1000, 3, 1 << 30, 1_500_000_000, 97 };
		Random random = new Random(CONTROL_SEED);
		int[] drawn = IntStream.of(bounds).map(random::nextInt).toArray();
		Assert.assertArrayEquals(new int[] { 0, 6, 389, 1, 918785025, 113334809, 91 }, drawn);
	}

	/**
	 * Asserts that {@link #assertDrivenSets} rejects the driven sets with one run replaced.
	 *
	 * @param connectome the loaded connectome
	 * @param driven     the accepted driven sets, left unchanged
	 * @param run        the run whose driven types are replaced
	 * @param names      the type names driven in that run instead
	 */
	private void assertDrivenSetsRejected(CellTypeConnectome connectome, JsonObject driven,
										  String run, List<String> names) {
		JsonObject altered = driven.deepCopy();
		JsonArray replacement = new JsonArray();
		names.forEach(replacement::add);
		altered.add(run, replacement);

		try {
			assertDrivenSets(connectome, altered);
		} catch (AssertionError expected) {
			return;
		}

		Assert.fail("Driving " + names + " in the " + run + " run was accepted");
	}

	/**
	 * Reads a JSON array of numbers.
	 *
	 * @param values the array
	 * @return its values
	 */
	private static double[] doubles(JsonArray values) {
		return StreamSupport.stream(values.spliterator(), false).mapToDouble(v -> v.getAsDouble()).toArray();
	}
}
