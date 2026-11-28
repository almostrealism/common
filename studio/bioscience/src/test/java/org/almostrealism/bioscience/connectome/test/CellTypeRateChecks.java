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
import org.almostrealism.bioscience.connectome.CellTypeConnectome;
import org.almostrealism.graph.RatePopulation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.util.TestSuiteBase;

import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * The rate model of the cell-type experiments, shared by the tests on the full MaleCNS cell-type
 * matrix ({@link CellTypeRateModelTest}) and on the committed sugar circuit cut out of it
 * ({@link CellTypeSugarCircuitTest}): the constants, the population with one unit per cell type
 * and the row scale {@code 1 / type_size}, and a run from zero rates with a set of types driven.
 *
 * <p>The constants are the ones {@code studio/bioscience/scripts/male_cns_cell_types.py} uses for
 * its FP64 reference runs, so the platform's rates can be compared with that script's.</p>
 */
public abstract class CellTypeRateChecks extends TestSuiteBase {
	/** The time step, in seconds. */
	protected static final double DT = 1e-3;

	/** The rate time constant, in seconds. */
	protected static final double TAU = 0.02;

	/** The maximum rate. */
	protected static final double R_MAX = 100.0;

	/** The global gain on the mean input per neuron. */
	protected static final double GAIN = 0.003;

	/** The drive written into the input of each driven type. */
	protected static final double DRIVE = 50.0;

	/**
	 * The largest absolute difference allowed between a rate the platform reaches and the FP64
	 * rate the reference script reached for the same run. Rates lie in {@code [0, R_MAX]}.
	 *
	 * <p>Set from the FP32 measurements on a Mac Studio (Apple silicon), the largest difference
	 * over every type and run: 1.2e-4 on the 250-type sugar circuit (native FP32 and Metal) and
	 * 1.2e-3 on the full 11,751-type matrix (Metal). Native FP64 differs by at most 1.1e-12.</p>
	 */
	protected static final double RATE_ALLOWANCE = 1e-2;

	/** The proboscis motor neuron type whose response is compared. */
	protected static final String MN9 = "MN9";

	/**
	 * Builds a population on the connectome with the row scale {@code 1 / type_size}.
	 *
	 * @param connectome the loaded connectome
	 * @return the population, to be destroyed by the caller
	 */
	protected RatePopulation population(CellTypeConnectome connectome) {
		return new RatePopulation(connectome.getWeights(), connectome.inverseTypeSizes(), GAIN, DT, TAU, R_MAX);
	}

	/** Returns the name of the current backend and precision, for the log. */
	protected String backend() {
		return Hardware.getLocalHardware().getComputeContext().getClass().getSimpleName()
				+ "/" + Hardware.getLocalHardware().getPrecision();
	}

	/**
	 * Returns the indices of the named types.
	 *
	 * @param connectome the loaded connectome
	 * @param names      a JSON array of type names
	 * @return the index of each type, in order
	 */
	protected int[] indices(CellTypeConnectome connectome, JsonArray names) {
		return StreamSupport.stream(names.spliterator(), false)
				.mapToInt(name -> connectome.indexOf(name.getAsString())).toArray();
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
	protected double response(SimulationRunner runner, RatePopulation population,
							  int target, int[] driven, int steps) {
		return responseRates(runner, population, driven, steps)[target];
	}

	/**
	 * Resets the population to zero rates, drives the given types, runs, and returns every
	 * type's rate.
	 *
	 * @param runner     the runner stepping the population
	 * @param population the population
	 * @param driven     the driven types
	 * @param steps      the number of steps
	 * @return the rate of every type after the run
	 */
	protected double[] responseRates(SimulationRunner runner, RatePopulation population,
									 int[] driven, int steps) {
		population.getRates().clear();
		population.getInput().clear();
		for (int i : driven) population.getInput().setMem(i, 1.0);
		a(cp(population.getInput()), cp(population.getInput()).multiply(DRIVE)).get().run();

		runner.reset();
		runner.run(steps);
		return population.getRates().toArray();
	}

	/**
	 * Returns the largest absolute difference between two vectors of rates.
	 *
	 * @param expected the expected rates
	 * @param actual   the rates reached
	 * @return the largest absolute difference
	 */
	protected double maxDifference(double[] expected, double[] actual) {
		assertEquals(expected.length, actual.length);
		return IntStream.range(0, expected.length)
				.mapToDouble(i -> Math.abs(expected[i] - actual[i])).max().orElse(0.0);
	}
}
