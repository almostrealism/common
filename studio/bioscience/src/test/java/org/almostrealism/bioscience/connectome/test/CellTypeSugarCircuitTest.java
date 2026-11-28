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
import org.junit.Test;

import java.io.File;
import java.io.IOException;
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
	/** The file of expected rates written by the script beside the matrix. */
	private static final String RATE_REFERENCE_FILE = "rate-reference.json";

	/** The fraction of the recorded GRN-over-control margin MN9 must keep on the platform. */
	private static final double MARGIN_FRACTION = 0.9;

	/**
	 * Returns the directory of the committed circuit.
	 *
	 * @return the directory
	 */
	private File circuit() {
		return NeuronConnectomeChecks.resourceDirectory(getClass(),
				"/connectome/cell-type-sugar-circuit/" + CellTypeConnectome.TYPES_FILE);
	}

	/**
	 * The circuit loads through {@link CellTypeConnectome#load} and every entry the script
	 * recomputed from the raw MaleCNS edge table equals the loaded weight exactly.
	 */
	@Test(timeout = 300000)
	public void spotChecks() throws IOException {
		log("spotChecks=" + CellTypeConnectomeTest.assertSpotChecks(circuit(), 90));
	}

	/**
	 * With no drive every rate stays exactly zero; driving the sugar GRNs, and then the matched
	 * control, for 1,000 steps from zero rates reaches the recorded rate of every kept type,
	 * MN9's included, within {@link #RATE_ALLOWANCE}; and MN9's GRN rise exceeds its control rise
	 * by at least {@value #MARGIN_FRACTION} of the recorded margin.
	 */
	@Test(timeout = 300000)
	public void sugarDrivesMn9() throws IOException {
		File directory = circuit();
		JsonObject reference = NeuronConnectomeChecks.json(directory, RATE_REFERENCE_FILE).getAsJsonObject();
		JsonObject driven = reference.getAsJsonObject("driven");
		JsonObject expectedFinal = reference.getAsJsonObject("final");
		int steps = reference.getAsJsonObject("constants").get("steps").getAsInt();

		try (CellTypeConnectome connectome = CellTypeConnectome.load(directory);
			 RatePopulation population = population(connectome)) {
			int mn9 = connectome.indexOf(MN9);
			SimulationRunner runner = new SimulationRunner(population, DT);

			double[] baseline = responseRates(runner, population, new int[0], steps);
			assertEquals(0.0, maxDifference(new double[baseline.length], baseline), 0.0);

			double[] mn9Final = new double[2];
			String[] runs = { "grn", "control" };
			for (int r = 0; r < runs.length; r++) {
				double[] expected = doubles(expectedFinal.getAsJsonArray(runs[r]));
				double[] actual = responseRates(runner, population, indices(connectome, driven.getAsJsonArray(runs[r])), steps);
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
	 * Reads a JSON array of numbers.
	 *
	 * @param values the array
	 * @return its values
	 */
	private static double[] doubles(JsonArray values) {
		return StreamSupport.stream(values.spliterator(), false).mapToDouble(v -> v.getAsDouble()).toArray();
	}
}
