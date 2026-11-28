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

import com.google.gson.JsonObject;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.graph.RatePopulation;
import org.almostrealism.bioscience.connectome.CellTypeConnectome;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Runs a {@link RatePopulation} on the MaleCNS cell-type matrix loaded by
 * {@link CellTypeConnectome}, one unit per cell type, with each row scaled by
 * {@code 1 / type_size} so that a type's input is the mean input per neuron of that type.
 *
 * <p>The cases are a long stability run, the sugar-sensing GRN to MN9 response against a matched
 * control, and the time per step. They need the derived data (the directory
 * {@link CellTypeConnectomeTest} reads), which no runner carries, so they are long-running and
 * excluded from the pipeline profile, and are meant to run on both the Metal and the native
 * backend when asked for. A missing directory then fails them on every host.</p>
 *
 * <p>Its scaled-down counterpart, which every CI lane runs, is {@link CellTypeSugarCircuitTest}:
 * the same experiment on a committed 250-type sub-matrix cut out of this one.
 * {@link #matchesRateReference()} ties the two together through the full-matrix reference that
 * sub-matrix was cut from.</p>
 *
 * <h2>Measured time per step</h2>
 * <p>Measured on a Mac Studio (Apple silicon, macOS, aarch64) for the 11,751-type matrix,
 * 1,000 timed steps after a 100-step warm-up (see {@link #timePerStep()}); native ran in FP64 and
 * Metal in FP32:</p>
 * <ul>
 *   <li>native: 14,527 microseconds per step;</li>
 *   <li>mtl: 11,380 microseconds per step.</li>
 * </ul>
 */
public class CellTypeRateModelTest extends CellTypeRateChecks {
	/** The property or environment variable naming the full-matrix rate reference directory. */
	public static final String RATES_PROPERTY = "AR_MALE_CNS_CELL_TYPE_RATES";

	/** The rate reference directory used when the property is not set. */
	public static final String DEFAULT_RATES_DIRECTORY = "/Users/Shared/models-derived/male-cns/cell-type-rate-reference";

	/** The manifest of the rate reference. */
	private static final String RATES_MANIFEST = "manifest.json";

	/**
	 * Loads the cell-type matrix from the directory {@link CellTypeConnectomeTest} reads, failing
	 * the calling test on every host when it is absent.
	 *
	 * @return the loaded connectome, to be closed by the caller
	 * @throws IOException if the matrix cannot be read
	 */
	private CellTypeConnectome loadConnectome() throws IOException {
		return CellTypeConnectome.load(requireResourceIn(CellTypeConnectomeTest.DIRECTORY_PROPERTY,
				CellTypeConnectomeTest.DEFAULT_DIRECTORY, CellTypeConnectome.WEIGHTS_FILE,
				ComputeRequirement.GPU, ComputeRequirement.CPU));
	}

	/**
	 * Runs the model for 10 s of simulated time (10,000 steps of 1 ms) from random rates in
	 * {@code [0, rMax]} with a small random input, recording every rate every 100 steps. Every
	 * recorded rate must be finite and within {@code [0, rMax]}. The change between the last two
	 * samples is logged, to show whether the rates settled.
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void stability() throws IOException {
		int steps = 10000;
		int interval = 100;

		try (CellTypeConnectome connectome = loadConnectome();
			 RatePopulation population = population(connectome)) {
			Random random = new Random(10);
			int n = population.getCount();
			a(cp(population.getRates()), rand(shape(n), random).multiply(R_MAX)).get().run();
			a(cp(population.getInput()), rand(shape(n), random).subtract(0.5)).get().run();

			SimulationRunner runner = new SimulationRunner(population, DT);
			SimulationRunner.Recording recording = runner.record(cp(population.getRates()), interval, steps / interval);
			runner.run(steps);

			double[] samples = recording.getBuffer().toArray();
			double min = Double.MAX_VALUE;
			double max = -Double.MAX_VALUE;
			for (double v : samples) {
				assertTrue("rate is not finite", Double.isFinite(v));
				min = Math.min(min, v);
				max = Math.max(max, v);
			}

			int last = steps / interval - 1;
			double lateChange = 0.0;
			int active = 0;
			for (int i = 0; i < n; i++) {
				lateChange = Math.max(lateChange, Math.abs(samples[last * n + i] - samples[(last - 1) * n + i]));
				if (samples[last * n + i] > 1e-3) active++;
			}

			log("backend=" + backend() + " min=" + min + " max=" + max
					+ " lateChange=" + lateChange + " activeTypes=" + active);
			assertTrue("rate below zero: " + min, min >= 0.0);
			assertTrue("rate above rMax: " + max, max <= R_MAX * (1.0 + 1e-5));
			runner.destroy();
		}
	}

	/**
	 * Driving the sugar-sensing GRN types raises the rate of MN9 more than driving a matched
	 * control set does. The control holds one sensory, non-gustatory type per GRN type, chosen
	 * with a fixed seed among the types whose outgoing synapse count is within 20% of that GRN
	 * type's. Every run starts from zero rates, with the same gain, and lasts 1 s; with no drive
	 * every rate stays at zero, so MN9's rate is its rise.
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void sugarDrivesMn9() throws IOException {
		int steps = STEPS;

		try (CellTypeConnectome connectome = loadConnectome();
			 RatePopulation population = population(connectome)) {
			int mn9 = connectome.indexOf(MN9);
			int[] grns = SUGAR_GRNS.stream().mapToInt(connectome::indexOf).toArray();
			int[] control = matchedControl(connectome, grns);
			log("control=" + Arrays.toString(IntStream.of(control).mapToObj(connectome.getTypeNames()::get).toArray()));
			assertEquals(MATCHED_CONTROL, names(connectome, control));

			SimulationRunner runner = new SimulationRunner(population, DT);
			double baseline = response(runner, population, mn9, new int[0], steps);
			double grn = response(runner, population, mn9, grns, steps);
			double matched = response(runner, population, mn9, control, steps);
			runner.destroy();

			log("backend=" + backend() + " mn9Baseline=" + baseline + " mn9Grn=" + grn + " mn9Control=" + matched);
			assertEquals(0.0, baseline, 0.0);
			assertTrue("GRN drive does not raise MN9", grn > baseline);
			assertTrue("GRN drive raises MN9 no more than control drive", grn - baseline > matched - baseline);
		}
	}

	/**
	 * Chooses, for each GRN type, one of its {@link #controlCandidates} (a sensory non-gustatory
	 * type whose outgoing synapse count is within 20% of the GRN type's) that is not already
	 * chosen.
	 *
	 * @param connectome the loaded connectome
	 * @param grns       the GRN type indices
	 * @return the control type indices
	 */
	private int[] matchedControl(CellTypeConnectome connectome, int[] grns) {
		Random random = new Random(CONTROL_SEED);
		List<Integer> chosen = new ArrayList<>();
		for (int grn : grns) {
			int[] candidates = IntStream.of(controlCandidates(connectome, grn))
					.filter(i -> !chosen.contains(i))
					.toArray();
			assertTrue("no control candidate for " + connectome.getTypeNames().get(grn), candidates.length > 0);
			chosen.add(candidates[random.nextInt(candidates.length)]);
		}

		return chosen.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * The platform reproduces the full-matrix rate reference written by
	 * {@code male_cns_cell_types.py rates}: the no-drive, sugar GRN and control runs its manifest
	 * names, each 1,000 steps from zero rates, reach every type's final rate in
	 * {@code rates.safetensors} within {@link #RATE_ALLOWANCE}, MN9's included. The reference is
	 * the full-matrix result the committed sugar circuit ({@link CellTypeSugarCircuitTest}) is
	 * traced to, so this closes the chain from the full matrix to that pipeline test.
	 *
	 * <p>The reference is not trusted to describe this experiment. Before any run, its driven
	 * sets must be the ones this class defines: no type for the baseline, {@link #SUGAR_GRNS}
	 * for the GRN run, and for the control exactly the types {@link #matchedControl} draws. They
	 * must also be the driven sets the committed circuit records, whose matrix checksum and
	 * full-matrix MN9 rates must be this reference's, the latter reached by the platform too.</p>
	 *
	 * <p>The reference lives outside the repository, in the directory named by
	 * {@value #RATES_PROPERTY} (by default {@value #DEFAULT_RATES_DIRECTORY}); a missing reference
	 * fails the test on every host.</p>
	 *
	 * <p>Measured on a Mac Studio (Apple silicon), the largest difference over every type and run
	 * was 1.1e-12 on native FP64 and 1.2e-3 on Metal (FP32), where MN9 reached 84.2473 (GRN) and
	 * 47.6821 (control) against the reference's 84.2472 and 47.6821.</p>
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void matchesRateReference() throws IOException {
		int steps = STEPS;
		File directory = requireResourceIn(RATES_PROPERTY, DEFAULT_RATES_DIRECTORY, RATES_MANIFEST,
				ComputeRequirement.GPU, ComputeRequirement.CPU);
		JsonObject manifest = NeuronConnectomeChecks.json(directory, RATES_MANIFEST).getAsJsonObject();
		JsonObject driven = manifest.getAsJsonObject("driven");
		JsonObject circuit = NeuronConnectomeChecks.json(sugarCircuit(), SUGAR_CIRCUIT_REFERENCE_FILE).getAsJsonObject();
		JsonObject circuitFull = circuit.getAsJsonObject("full_matrix");

		try (CellTypeConnectome connectome = loadConnectome();
			 RatePopulation population = population(connectome);
			 StateDictionary reference = new StateDictionary(directory.getPath())) {
			int mn9 = connectome.indexOf(MN9);
			assertConstants(manifest.getAsJsonObject("constants"));
			assertDrivenSets(connectome, driven);
			assertEquals(names(connectome, matchedControl(connectome, indices(connectome, SUGAR_GRNS))),
					names(driven.getAsJsonArray("control")));
			assertEquals(driven, circuit.getAsJsonObject("driven"));
			assertEquals(manifest.get("matrix_sha256").getAsString(), circuitFull.get("matrix_sha256").getAsString());

			SimulationRunner runner = new SimulationRunner(population, DT);
			for (String run : List.of("baseline", "grn", "control")) {
				double[] expected = reference.require(run + "_final").toArray();
				double[] actual = responseRates(runner, population,
						indices(connectome, names(driven.getAsJsonArray(run))), steps);
				double difference = maxDifference(expected, actual);
				log("backend=" + backend() + " run=" + run + " mn9Expected=" + expected[mn9]
						+ " mn9Actual=" + actual[mn9] + " maxDifference=" + difference);
				assertEquals(run, expected[mn9], actual[mn9], RATE_ALLOWANCE);
				assertTrue(run + " differs by " + difference, difference <= RATE_ALLOWANCE);
				assertEquals(run, circuitFull.getAsJsonObject("mn9_final").get(run).getAsDouble(),
						actual[mn9], RATE_ALLOWANCE);
			}

			runner.destroy();
		}
	}

	/**
	 * Logs the time per step ({@code perStepMicros=}) over 1,000 steps after a 100-step warm-up,
	 * in the style of {@code SimulationRunnerDispatchTest}. Only the rates' finiteness is
	 * asserted, never a time threshold.
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void timePerStep() throws IOException {
		int warmup = 100;
		int steps = 1000;

		try (CellTypeConnectome connectome = loadConnectome();
			 RatePopulation population = population(connectome)) {
			Random random = new Random(1000);
			a(cp(population.getRates()), rand(shape(population.getCount()), random).multiply(R_MAX)).get().run();

			SimulationRunner runner = new SimulationRunner(population, DT);
			runner.run(warmup);
			long start = System.nanoTime();
			runner.run(steps);
			double micros = (System.nanoTime() - start) / 1000.0 / steps;
			runner.destroy();

			log("backend=" + backend() + " types=" + population.getCount() + " perStepMicros=" + micros);
			assertTrue(Double.isFinite(population.getRates().toDouble(0)));
		}
	}
}
