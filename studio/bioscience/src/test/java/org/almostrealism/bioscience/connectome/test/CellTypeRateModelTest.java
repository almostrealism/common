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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.RatePopulation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.bioscience.connectome.CellTypeConnectome;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

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
 * control, and the time per step. They need the derived data (see
 * {@link CellTypeConnectomeTest#requireDirectory()}) and are meant to run on both the Metal and
 * the native backend.</p>
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
public class CellTypeRateModelTest extends TestSuiteBase {
	/** The time step, in seconds. */
	private static final double DT = 1e-3;

	/** The rate time constant, in seconds. */
	private static final double TAU = 0.02;

	/** The maximum rate. */
	private static final double R_MAX = 100.0;

	/** The global gain on the mean input per neuron. */
	private static final double GAIN = 0.003;

	/** The drive written into the input of each driven type. */
	private static final double DRIVE = 50.0;

	/**
	 * The MaleCNS types taken as the sugar-sensing gustatory receptor neurons: the labellar
	 * bristle types that MaleCNS matches to the FlyWire type {@code LB3}. They are chosen because
	 * their multi-hop signed path to MN9 is the most excitatory among the labellar types, as
	 * expected of sugar GRNs, whose activation drives proboscis extension.
	 */
	private static final List<String> SUGAR_GRNS = List.of("LB3a", "LB3b", "LB3c", "LB3d");

	/** The proboscis motor neuron type whose response is compared. */
	private static final String MN9 = "MN9";

	/** The seed used to choose the control types. */
	private static final long CONTROL_SEED = 20261026L;

	/**
	 * Builds a population on the connectome with the row scale {@code 1 / type_size}.
	 *
	 * @param connectome the loaded connectome
	 * @return the population, to be destroyed by the caller
	 */
	private RatePopulation population(CellTypeConnectome connectome) {
		return new RatePopulation(connectome.getWeights(), connectome.inverseTypeSizes(), GAIN, DT, TAU, R_MAX);
	}

	/** Returns the name of the current backend and precision, for the log. */
	private String backend() {
		return Hardware.getLocalHardware().getComputeContext().getClass().getSimpleName()
				+ "/" + Hardware.getLocalHardware().getPrecision();
	}

	/**
	 * Runs the model for 10 s of simulated time (10,000 steps of 1 ms) from random rates in
	 * {@code [0, rMax]} with a small random input, recording every rate every 100 steps. Every
	 * recorded rate must be finite and within {@code [0, rMax]}. The change between the last two
	 * samples is logged, to show whether the rates settled.
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	public void stability() throws IOException {
		int steps = 10000;
		int interval = 100;

		try (CellTypeConnectome connectome = CellTypeConnectome.load(CellTypeConnectomeTest.requireDirectory());
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
	public void sugarDrivesMn9() throws IOException {
		int steps = 1000;

		try (CellTypeConnectome connectome = CellTypeConnectome.load(CellTypeConnectomeTest.requireDirectory());
			 RatePopulation population = population(connectome)) {
			int mn9 = connectome.indexOf(MN9);
			int[] grns = SUGAR_GRNS.stream().mapToInt(connectome::indexOf).toArray();
			int[] control = matchedControl(connectome, grns);
			log("control=" + Arrays.toString(IntStream.of(control).mapToObj(connectome.getTypeNames()::get).toArray()));

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
	 * Chooses, for each GRN type, a sensory non-gustatory type (not already chosen) whose outgoing
	 * synapse count is within 20% of the GRN type's.
	 *
	 * @param connectome the loaded connectome
	 * @param grns       the GRN type indices
	 * @return the control type indices
	 */
	private int[] matchedControl(CellTypeConnectome connectome, int[] grns) {
		Random random = new Random(CONTROL_SEED);
		PackedCollection out = connectome.getOutSynapses();
		List<Integer> chosen = new ArrayList<>();
		for (int grn : grns) {
			double target = out.toDouble(grn);
			int[] candidates = IntStream.range(0, connectome.getTypeCount())
					.filter(i -> connectome.getSuperclass(i).contains("sensory"))
					.filter(i -> !"gustatory".equals(connectome.getTypeClass(i)))
					.filter(i -> Math.abs(out.toDouble(i) - target) <= 0.2 * target)
					.filter(i -> !chosen.contains(i))
					.toArray();
			assertTrue("no control candidate for " + connectome.getTypeNames().get(grn), candidates.length > 0);
			chosen.add(candidates[random.nextInt(candidates.length)]);
		}

		return chosen.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Resets the population to zero rates, drives the given types, runs, and returns the rate of
	 * the target type.
	 *
	 * @param runner     the runner stepping the population
	 * @param population the population
	 * @param target     the type whose rate is returned
	 * @param driven     the driven types
	 * @param steps      the number of steps
	 * @return the target's rate after the run
	 */
	private double response(SimulationRunner runner, RatePopulation population,
							int target, int[] driven, int steps) {
		population.getRates().clear();
		population.getInput().clear();
		for (int i : driven) population.getInput().setMem(i, 1.0);
		a(cp(population.getInput()), cp(population.getInput()).multiply(DRIVE)).get().run();

		runner.reset();
		runner.run(steps);
		return population.getRates().toDouble(target);
	}

	/**
	 * Logs the time per step ({@code perStepMicros=}) over 1,000 steps after a 100-step warm-up,
	 * in the style of {@code SimulationRunnerDispatchTest}. Only the rates' finiteness is
	 * asserted, never a time threshold.
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	public void timePerStep() throws IOException {
		int warmup = 100;
		int steps = 1000;

		try (CellTypeConnectome connectome = CellTypeConnectome.load(CellTypeConnectomeTest.requireDirectory());
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
