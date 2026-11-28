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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.almostrealism.bioscience.connectome.CellTypeConnectome;
import org.almostrealism.graph.RatePopulation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.util.TestSuiteBase;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * The rate model of the cell-type experiments, shared by the tests on the full MaleCNS cell-type
 * matrix ({@link CellTypeRateModelTest}) and on the committed sugar circuit cut out of it
 * ({@link CellTypeSugarCircuitTest}): the constants, the driven types and the rule their control
 * follows, the population with one unit per cell type and the row scale {@code 1 / type_size},
 * and a run from zero rates with a set of types driven.
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
	 * The MaleCNS types taken as the sugar-sensing gustatory receptor neurons: the labellar
	 * bristle types that MaleCNS matches to the FlyWire type {@code LB3}. They are chosen because
	 * their multi-hop signed path to MN9 is the most excitatory among the labellar types, as
	 * expected of sugar GRNs, whose activation drives proboscis extension.
	 */
	protected static final List<String> SUGAR_GRNS = List.of("LB3a", "LB3b", "LB3c", "LB3d");

	/**
	 * The seed of the {@link java.util.Random} that draws the control types on the full matrix.
	 * The conversion script draws with a port of that generator from the same seed.
	 */
	protected static final long CONTROL_SEED = 20261026L;

	/** The file of the committed sugar circuit holding its driven types and expected rates. */
	protected static final String SUGAR_CIRCUIT_REFERENCE_FILE = "rate-reference.json";

	/**
	 * Returns the directory of the committed cell-type sugar circuit.
	 *
	 * @return the directory
	 */
	protected static File sugarCircuit() {
		return NeuronConnectomeChecks.resourceDirectory(CellTypeRateChecks.class,
				"/connectome/cell-type-sugar-circuit/" + CellTypeConnectome.TYPES_FILE);
	}

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
	 * Reads a JSON array of type names.
	 *
	 * @param names the array
	 * @return the names, in order
	 */
	protected static List<String> names(JsonArray names) {
		return StreamSupport.stream(names.spliterator(), false)
				.map(JsonElement::getAsString).collect(Collectors.toList());
	}

	/**
	 * Returns the names of the types at the given indices.
	 *
	 * @param connectome the loaded connectome
	 * @param indices    the type indices
	 * @return the name of each type, in order
	 */
	protected static List<String> names(CellTypeConnectome connectome, int[] indices) {
		return IntStream.of(indices).mapToObj(connectome.getTypeNames()::get).collect(Collectors.toList());
	}

	/**
	 * Returns the indices of the named types.
	 *
	 * @param connectome the loaded connectome
	 * @param names      the type names
	 * @return the index of each type, in order
	 */
	protected static int[] indices(CellTypeConnectome connectome, List<String> names) {
		return names.stream().mapToInt(connectome::indexOf).toArray();
	}

	/**
	 * Returns the types that may serve as the control of a GRN type: those whose superclass
	 * contains {@code sensory}, whose class is not {@code gustatory}, and whose outgoing synapse
	 * count is within 20% of the GRN type's. This is the rule the full-matrix test draws its
	 * control by, and the one {@code matched_control} of the conversion script ports.
	 *
	 * @param connectome the loaded connectome
	 * @param grn        the GRN type index
	 * @return the candidate type indices, in index order
	 */
	protected static int[] controlCandidates(CellTypeConnectome connectome, int grn) {
		double[] out = connectome.getOutSynapses().toArray();
		double target = out[grn];
		return IntStream.range(0, connectome.getTypeCount())
				.filter(i -> connectome.getSuperclass(i).contains("sensory"))
				.filter(i -> !"gustatory".equals(connectome.getTypeClass(i)))
				.filter(i -> Math.abs(out[i] - target) <= 0.2 * target)
				.toArray();
	}

	/**
	 * Checks that the driven sets a reference records are the experiment these tests define, so
	 * that a stale or independently drawn reference cannot stand in for it: no type is driven in
	 * the baseline run, the GRN run drives {@link #SUGAR_GRNS}, and the control run drives one
	 * distinct type per GRN type, each among that GRN type's {@link #controlCandidates}.
	 *
	 * @param connectome the loaded connectome the reference belongs to
	 * @param driven     the reference's driven type names, keyed by run
	 */
	protected void assertDrivenSets(CellTypeConnectome connectome, JsonObject driven) {
		assertEquals(List.of(), names(driven.getAsJsonArray("baseline")));
		assertEquals(SUGAR_GRNS, names(driven.getAsJsonArray("grn")));

		List<String> control = names(driven.getAsJsonArray("control"));
		assertEquals("one control type per GRN type", SUGAR_GRNS.size(), control.size());
		assertEquals("a control type is repeated", control.size(), (int) control.stream().distinct().count());
		for (int i = 0; i < control.size(); i++) {
			int type = connectome.indexOf(control.get(i));
			int[] candidates = controlCandidates(connectome, connectome.indexOf(SUGAR_GRNS.get(i)));
			assertTrue(control.get(i) + " is not a control candidate for " + SUGAR_GRNS.get(i),
					IntStream.of(candidates).anyMatch(candidate -> candidate == type));
		}
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
