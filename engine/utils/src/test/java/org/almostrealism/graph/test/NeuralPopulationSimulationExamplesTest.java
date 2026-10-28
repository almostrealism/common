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

package org.almostrealism.graph.test;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.LeakyIntegrateAndFirePopulation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.OperationListRunner;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * Pins the worked example of the neural population simulation internals page
 * ({@code docs/internals/neural-population-simulation.md}): an input population of
 * {@link LeakyIntegrateAndFirePopulation} neurons, half of them under a masked Poisson drive,
 * connected by a dense weight matrix to an output population, the two stepped together by a
 * {@link SimulationRunner} through a {@link Temporal} adapter, with the input spike vector and the
 * output conductance recorded into device buffers that are read only after the run.
 *
 * <p>The network is built by {@link Network}, which is the code the page shows. The tests assert
 * the page's claims about it: the driven rate, the silence of undriven neurons, the exact synaptic
 * delay between the two populations under the tick order the page uses, reproducibility of the
 * spike raster for a seed (including a repeat run after a reset), and the destruction order of the
 * runner and the populations. If one of these assertions fails, the page is wrong and must be
 * corrected together with whatever changed.</p>
 *
 * @author Michael Murray
 */
public class NeuralPopulationSimulationExamplesTest extends TestSuiteBase {
	/** The simulation time step, in milliseconds. */
	private static final double DT = 0.1;

	/** The number of input neurons. */
	private static final int INPUTS = 8;

	/** The number of input neurons under the Poisson drive (the first {@code DRIVEN}). */
	private static final int DRIVEN = 4;

	/** The number of output neurons. */
	private static final int OUTPUTS = 2;

	/** The synaptic delay of both populations, in steps. */
	private static final int DELAY_STEPS = 5;

	/** The Poisson rate of a driven input neuron, in spikes per millisecond (500 Hz). */
	private static final double RATE = 0.5;

	/** The weight of every input-to-output connection. */
	private static final double WEIGHT = 0.5;

	/** The number of steps each simulation runs. */
	private static final int STEPS = 2000;

	/** The seed of the Poisson drive. */
	private static final int SEED = 7;

	/**
	 * The worked example of the page: two populations, a dense connection between them, a
	 * {@link Temporal} adapter that steps both, a {@link SimulationRunner} that owns the loop and
	 * two recordings. {@link #destroy()} releases them in the order the page prescribes: the
	 * runner (and with it the compiled step and the recordings) before the populations.
	 */
	private class Network implements Destroyable {
		/** The input population, partly under the Poisson drive. */
		final LeakyIntegrateAndFirePopulation input;

		/** The output population, driven only through the connection from the input. */
		final LeakyIntegrateAndFirePopulation output;

		/** The dense connection, {@code [OUTPUTS, INPUTS]}, row = target, column = source. */
		final PackedCollection weights;

		/** The input-set mask of the Poisson drive. */
		final PackedCollection mask;

		/** The (empty) synaptic input of the input population. */
		final PackedCollection silence;

		/** The simulation loop owner. */
		final SimulationRunner runner;

		/** The recording of the input spike vector after every step. */
		final SimulationRunner.Recording raster;

		/** The recording of the output conductance after every step. */
		final SimulationRunner.Recording received;

		/**
		 * Builds the network with the given Poisson seed, stepping the output before the input.
		 *
		 * @param seed the seed of the input population's Poisson drive
		 */
		Network(int seed) {
			this(seed, true);
		}

		/**
		 * Builds the network with the given Poisson seed and tick order.
		 *
		 * @param seed        the seed of the input population's Poisson drive
		 * @param outputFirst whether the tick steps the output population before the input
		 */
		Network(int seed, boolean outputFirst) {
			// Two populations: dt, tauM, tauSyn, vRest, vReset, vThreshold, R,
			// refractory steps, delay steps
			input = new LeakyIntegrateAndFirePopulation(INPUTS, DT, 20.0, 5.0,
					0.0, 0.0, 1.0, 1.0, 20, DELAY_STEPS);
			output = new LeakyIntegrateAndFirePopulation(OUTPUTS, DT, 20.0, 5.0,
					0.0, 0.0, 1.0, 1.0, 20, DELAY_STEPS);

			// The first DRIVEN input neurons fire at RATE; setPoissonDrive comes before step(...)
			mask = lessThan(integers(0, INPUTS), c((double) DRIVEN), c(1.0), c(0.0)).evaluate();
			input.setPoissonDrive(RATE, mask, seed);
			input.reset();

			// Dense connectivity: the output's synaptic input is W times the input's delayed spikes
			weights = new PackedCollection(shape(OUTPUTS, INPUTS)).fill(WEIGHT);
			silence = new PackedCollection(shape(INPUTS));
			Producer<PackedCollection> synapticInput = matmul(cp(weights), input.delayedSpikes());

			// One tick steps the output before the input, so the delay is exactly DELAY_STEPS
			Temporal network = outputFirst ? () -> {
				OperationList tick = new OperationList("two-population tick");
				tick.add(output.step(synapticInput));
				tick.add(input.step(cp(silence)));
				return tick;
			} : () -> {
				OperationList tick = new OperationList("two-population tick, input first");
				tick.add(input.step(cp(silence)));
				tick.add(output.step(synapticInput));
				return tick;
			};

			runner = new SimulationRunner(network, DT);
			raster = runner.record(cp(input.getSpikes()), 1, STEPS);
			received = runner.record(cp(output.getConductance()), 1, STEPS);
		}

		/**
		 * Returns the network to its initial condition for a repeat run with the same seed: the
		 * runner, whose reset does not reach the populations, and each population's own reset.
		 */
		void reset() {
			runner.reset();
			input.reset();
			output.reset();
		}

		/** Releases the runner first, then the populations and the collections they read. */
		@Override
		public void destroy() {
			runner.destroy();
			input.destroy();
			output.destroy();
			weights.destroy();
			mask.destroy();
			silence.destroy();
		}
	}

	/**
	 * Each driven input neuron fires at the configured rate, and no undriven input neuron fires.
	 *
	 * <p>The input population receives no synaptic input and its membrane rests below threshold,
	 * so every input spike comes from the drive. The drive is combined with the threshold spike by
	 * {@code max} without consulting the refractory countdown, so each driven neuron spikes
	 * independently with probability {@code p = RATE * DT} on every step. The spike count over
	 * {@code n = DRIVEN * STEPS} neuron-steps is therefore Binomial(n, p), and the test accepts a
	 * measured count within five standard deviations, {@code 5 * sqrt(n p (1 - p))}, of
	 * {@code n p}. With {@code p = 0.05} and {@code n = 8000} that is {@code 400 +/- 97}.</p>
	 */
	@Test(timeout = 5 * 60000)
	public void drivenRateAndSilence() {
		Network net = new Network(SEED);
		try {
			net.runner.run(STEPS);
			double[] raster = net.raster.getBuffer().toArray();

			double p = RATE * DT;
			double n = (double) DRIVEN * STEPS;
			double tolerance = 5.0 * Math.sqrt(n * p * (1.0 - p));

			double driven = 0.0;
			for (int t = 0; t < STEPS; t++) {
				for (int i = 0; i < INPUTS; i++) {
					double spike = raster[t * INPUTS + i];
					if (i < DRIVEN) {
						driven += spike;
					} else {
						Assert.assertEquals("undriven neuron " + i + " at step " + t, 0.0, spike, 0.0);
					}
				}
			}

			log("drivenSpikes=" + driven + " expected=" + (n * p) + " tolerance=" + tolerance);
			Assert.assertEquals(n * p, driven, tolerance);
		} finally {
			net.destroy();
		}
	}

	/**
	 * An input spike reaches the output population exactly {@code DELAY_STEPS} steps later when the
	 * tick steps the output before the input: the output conductance is zero at every step before
	 * {@code s0 + DELAY_STEPS}, where {@code s0} is the first step at which any input neuron fires,
	 * and non-zero at that step.
	 */
	@Test(timeout = 5 * 60000)
	public void inputSpikeArrivesAfterExactDelay() {
		Assert.assertEquals(DELAY_STEPS, measuredDelay(true));
	}

	/**
	 * Stepping the input population before the output shortens the delay by one step: the output
	 * reads the input's ring after the input has already advanced its write index in the same
	 * tick, so the spike arrives {@code DELAY_STEPS - 1} steps after it was emitted. This is why
	 * the page's tick steps the output first.
	 */
	@Test(timeout = 5 * 60000)
	public void inputFirstShortensDelayByOneStep() {
		Assert.assertEquals(DELAY_STEPS - 1, measuredDelay(false));
	}

	/**
	 * Runs a network with the given tick order and returns the number of steps between the first
	 * input spike and the first step at which the output conductance is non-zero.
	 *
	 * @param outputFirst whether the tick steps the output population before the input
	 * @return the measured synaptic delay in steps
	 */
	private int measuredDelay(boolean outputFirst) {
		Network net = new Network(SEED, outputFirst);
		try {
			net.runner.run(STEPS);
			double[] raster = net.raster.getBuffer().toArray();
			double[] received = net.received.getBuffer().toArray();

			int firstSpike = -1;
			for (int t = 0; t < STEPS && firstSpike < 0; t++) {
				for (int i = 0; i < INPUTS; i++) {
					if (raster[t * INPUTS + i] > 0.5) {
						firstSpike = t;
						break;
					}
				}
			}

			Assert.assertTrue("no input neuron fired", firstSpike >= 0);
			Assert.assertTrue("first spike too late to observe its arrival",
					firstSpike + DELAY_STEPS < STEPS);

			int firstReceived = -1;
			for (int t = 0; t < STEPS && firstReceived < 0; t++) {
				if (received[t * OUTPUTS] != 0.0) {
					firstReceived = t;
				}
			}

			log("outputFirst=" + outputFirst + " firstInputSpike=" + firstSpike
					+ " firstOutputConductance=" + firstReceived);
			Assert.assertTrue("no input spike reached the output", firstReceived >= 0);
			return firstReceived - firstSpike;
		} finally {
			net.destroy();
		}
	}

	/**
	 * The same seed records the same raster, both from a second network and from a repeat run of
	 * the first network after {@link Network#reset()}; a different seed records a different raster.
	 * Destroying the runner leaves the populations' state collections allocated, so the order the
	 * page prescribes (runner first, then populations) releases each collection exactly once.
	 */
	@Test(timeout = 5 * 60000)
	public void rasterIsReproducibleForASeed() {
		Network first = new Network(SEED);
		Network same = new Network(SEED);
		Network other = new Network(SEED + 1);

		try {
			first.runner.run(STEPS);
			double[] original = first.raster.getBuffer().toArray();

			first.reset();
			first.runner.run(STEPS);
			Assert.assertArrayEquals("repeat run after reset", original,
					first.raster.getBuffer().toArray(), 0.0);

			same.runner.run(STEPS);
			Assert.assertArrayEquals("second network, same seed", original,
					same.raster.getBuffer().toArray(), 0.0);

			other.runner.run(STEPS);
			double[] different = other.raster.getBuffer().toArray();
			boolean differs = false;
			for (int i = 0; i < original.length && !differs; i++) {
				differs = original[i] != different[i];
			}
			Assert.assertTrue("a different seed recorded an identical raster", differs);

			first.runner.destroy();
			for (PackedCollection state : List.of(first.input.getSpikes(), first.input.getRing(),
					first.output.getConductance(), first.weights)) {
				Assert.assertFalse("destroying the runner released population state",
						state.isDestroyed());
			}
		} finally {
			first.input.destroy();
			first.output.destroy();
			first.weights.destroy();
			first.mask.destroy();
			first.silence.destroy();
			same.destroy();
			other.destroy();
		}
	}

	/**
	 * The step of a Poisson-driven population is not compiled to one kernel when it is run
	 * directly with {@link OperationList#get()}: its assignments have different parallelisms (the
	 * state assignments one thread per neuron, the ring write one per ring element, the write-index
	 * and counter advances one), so the list is not uniform and {@link OperationList#get()} returns
	 * an {@link OperationListRunner} that dispatches each of its eight operations separately, the
	 * same per-assignment pattern {@link SimulationRunner} uses. The profiles of both paths are
	 * written to {@code results/} for inspection.
	 *
	 * @throws IOException if a profile cannot be written
	 */
	@Test(timeout = 5 * 60000)
	public void stepDispatchesEachAssignment() throws IOException {
		LeakyIntegrateAndFirePopulation direct = drivenPopulation();
		LeakyIntegrateAndFirePopulation stepped = drivenPopulation();
		PackedCollection silence = new PackedCollection(shape(INPUTS));
		PackedCollection counter = new PackedCollection(1);

		OperationProfileNode directProfile = new OperationProfileNode("lifStepDirect");
		Runnable step = direct.step(cp(silence)).get(directProfile);

		OperationProfileNode runnerProfile = new OperationProfileNode("lifStepRunner");
		SimulationRunner runner = new SimulationRunner(() -> stepped.step(cp(silence)), DT,
				counter, runnerProfile);

		try {
			step.run();
			Assert.assertTrue("the step compiled to a single kernel: " + step.getClass().getName(),
					step instanceof OperationListRunner);
			Assert.assertEquals(8, ((OperationListRunner) step).getOperations().size());

			runner.run(10);
			directProfile.save("results/lifStepDirect.xml");
			runnerProfile.save("results/lifStepRunner.xml");
		} finally {
			Destroyable.destroy(step);
			runner.destroy();
			direct.destroy();
			stepped.destroy();
			silence.destroy();
			counter.destroy();
		}
	}

	/**
	 * Creates an input-sized population with a Poisson drive on every neuron.
	 *
	 * @return the driven population, reset
	 */
	private LeakyIntegrateAndFirePopulation drivenPopulation() {
		LeakyIntegrateAndFirePopulation pop = new LeakyIntegrateAndFirePopulation(INPUTS, DT, 20.0, 5.0,
				0.0, 0.0, 1.0, 1.0, 20, DELAY_STEPS);
		pop.setPoissonDrive(RATE, null, SEED);
		pop.reset();
		return pop;
	}
}
