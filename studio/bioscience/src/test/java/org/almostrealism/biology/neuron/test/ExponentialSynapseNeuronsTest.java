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

package org.almostrealism.biology.neuron.test;

import io.almostrealism.lifecycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.biology.neuron.ExponentialSynapseNeurons;
import org.almostrealism.biology.neuron.PoissonInput;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;

/**
 * Pins the step of {@link ExponentialSynapseNeurons} to the dynamics it documents, with the
 * constants of the Shiu et al. model (milliseconds and millivolts), and checks the statistics of
 * {@link PoissonInput}. Each test drives the population directly with an input built from the
 * runner's step counter, so the expected values follow from the equations alone.
 */
public class ExponentialSynapseNeuronsTest extends TestSuiteBase {
	/** The duration of one step. */
	private static final double DT = 0.1;

	/** The resting and reset potential. */
	private static final double REST = -52.0;

	/** The membrane time constant. */
	private static final double MEMBRANE_TIME = 20.0;

	/** The synaptic time constant. */
	private static final double SYNAPTIC_TIME = 5.0;

	/**
	 * Creates a population with the model's constants.
	 *
	 * @param count the number of neurons
	 * @return the population
	 */
	private ExponentialSynapseNeurons neurons(int count) {
		return new ExponentialSynapseNeurons(count, DT, REST, REST, -45.0, MEMBRANE_TIME, SYNAPTIC_TIME, 2.2);
	}

	/**
	 * Returns a value on step zero only.
	 *
	 * @param step  the step counter
	 * @param value the value of step zero
	 * @return a producer of the value on step zero and zero afterwards
	 */
	private CollectionProducer onFirstStep(PackedCollection step, double value) {
		return lessThan(cp(step), c(0.5), c(value), c(0.0));
	}

	/**
	 * Returns a runner that steps the population on a given input and returns it to rest before
	 * the first step.
	 *
	 * @param neurons the population
	 * @param step    the step counter
	 * @param input   producer of the {@code 2 * count} input values: membrane kicks, then synaptic
	 *                input
	 * @return the runner
	 */
	private SimulationRunner runner(ExponentialSynapseNeurons neurons, PackedCollection step,
									Producer<PackedCollection> input) {
		return new SimulationRunner(new DrivenNeurons(neurons, input), DT, step);
	}

	/** A population stepped on a fixed input, whose setup is the population's. */
	private static final class DrivenNeurons implements Temporal, Setup {
		/** The population. */
		private final ExponentialSynapseNeurons neurons;

		/** The input of every step. */
		private final Producer<PackedCollection> input;

		/**
		 * Creates the system.
		 *
		 * @param neurons the population
		 * @param input   the input of every step
		 */
		DrivenNeurons(ExponentialSynapseNeurons neurons, Producer<PackedCollection> input) {
			this.neurons = neurons;
			this.input = input;
		}

		@Override
		public Supplier<Runnable> tick() { return neurons.forward(input); }

		@Override
		public Supplier<Runnable> setup() { return neurons.setup(); }
	}

	/**
	 * After one synaptic input below threshold, the membrane follows the exact solution of the
	 * coupled equations: {@code v - v0 = g0 * tauS / (tauS - tauM) * (exp(-t / tauS) - exp(-t / tauM))}.
	 */
	@Test(timeout = 120000)
	public void membraneFollowsExactSolution() {
		int steps = 200;
		double g0 = 10.0;
		try (ExponentialSynapseNeurons neurons = neurons(1);
			 PackedCollection step = new PackedCollection(1)) {
			SimulationRunner runner = runner(neurons, step, concat(c(0.0), onFirstStep(step, g0)));
			SimulationRunner.Recording membrane = runner.record(cp(neurons.getMembrane()), 1, steps);
			runner.run(steps);

			double[] recorded = membrane.getBuffer().toArray();
			for (int j = 0; j < steps; j++) {
				double t = j * DT;
				double expected = REST + g0 * SYNAPTIC_TIME / (SYNAPTIC_TIME - MEMBRANE_TIME)
						* (Math.exp(-t / SYNAPTIC_TIME) - Math.exp(-t / MEMBRANE_TIME));
				Assert.assertEquals("step " + j, expected, recorded[j], 1e-4);
			}

			Assert.assertEquals(0.0, neurons.getSpikeCounts().toDouble(0), 0.0);
			runner.destroy();
		}
	}

	/**
	 * A kick above threshold makes the neuron spike on the next step, and the spike resets the
	 * membrane, clears the synaptic potential (including input that arrived on the same step) and
	 * starts the 22-step refractory countdown. Synaptic input arriving during the 21 held steps is
	 * discarded; the first active step after them adds it again.
	 */
	@Test(timeout = 120000)
	public void spikeResetsMembraneAndSynapse() {
		try (ExponentialSynapseNeurons neurons = neurons(1);
			 PackedCollection step = new PackedCollection(1)) {
			SimulationRunner runner = runner(neurons, step, concat(onFirstStep(step, 10.0), c(3.0)));
			runner.run(1);
			Assert.assertEquals(0.0, neurons.getSpikes().toDouble(0), 0.0);
			Assert.assertEquals(-42.0, neurons.getMembrane().toDouble(0), 1e-5);
			Assert.assertEquals(3.0, neurons.getSynapse().toDouble(0), 1e-6);

			runner.run(1);
			Assert.assertEquals(1.0, neurons.getSpikes().toDouble(0), 0.0);
			Assert.assertEquals(REST, neurons.getMembrane().toDouble(0), 1e-6);
			Assert.assertEquals(0.0, neurons.getSynapse().toDouble(0), 0.0);
			Assert.assertEquals(22, neurons.getRefractorySteps());
			Assert.assertEquals(22.0, neurons.getRefractory().toDouble(0), 0.0);
			Assert.assertEquals(1.0, neurons.getSpikeCounts().toDouble(0), 0.0);

			runner.run(21);
			Assert.assertEquals(REST, neurons.getMembrane().toDouble(0), 1e-6);
			Assert.assertEquals(0.0, neurons.getSynapse().toDouble(0), 0.0);

			runner.run(1);
			Assert.assertEquals(3.0, neurons.getSynapse().toDouble(0), 1e-6);
			runner.destroy();
		}
	}

	/**
	 * Under a supra-threshold kick on every step, a neuron with the refractory period spikes on
	 * steps {@code 1 + 23 k}: the kicks of the spike step and the 21 held steps after it are
	 * discarded, the kick of the first active step brings it above threshold, and it spikes on the
	 * step after, as in Brian2. An exempt neuron spikes on every other step, because the kick of a
	 * spike step is cleared by the reset.
	 */
	@Test(timeout = 120000)
	public void refractoryPeriodSetsTheInterval() {
		int steps = 220;
		try (ExponentialSynapseNeurons neurons = neurons(2);
			 PackedCollection exempt = new PackedCollection(2);
			 PackedCollection input = new PackedCollection(4);
			 PackedCollection step = new PackedCollection(1)) {
			exempt.fill(0.0, 1.0);
			input.fill(10.0, 10.0, 0.0, 0.0);
			neurons.exemptFromRefractoryPeriod(exempt);
			SimulationRunner runner = runner(neurons, step, cp(input));
			SimulationRunner.Recording spikes = runner.record(cp(neurons.getSpikes()), 1, steps);
			runner.run(steps);

			double[] recorded = spikes.getBuffer().toArray();
			for (int s = 0; s < steps; s++) {
				Assert.assertEquals("neuron 0 step " + s, s % 23 == 1 ? 1.0 : 0.0, recorded[2 * s], 0.0);
				Assert.assertEquals("neuron 1 step " + s, s % 2 == 1 ? 1.0 : 0.0, recorded[2 * s + 1], 0.0);
			}

			Assert.assertEquals(10.0, neurons.getSpikeCounts().toDouble(0), 0.0);
			Assert.assertEquals(110.0, neurons.getSpikeCounts().toDouble(1), 0.0);
			runner.destroy();
		}
	}

	/**
	 * Each target of a Poisson input is kicked with probability {@code rate * dt} per step,
	 * independently: over 1000 neurons and 1000 steps at 150 Hz the number of kicks is within four
	 * standard deviations of 15,000, a neuron outside the targets is never kicked, and a seed
	 * reproduces its draws.
	 */
	@Test(timeout = 120000)
	public void poissonKickRate() {
		int count = 1000;
		int steps = 1000;
		try (PackedCollection targets = new PackedCollection(count);
			 PackedCollection step = new PackedCollection(1);
			 PackedCollection total = new PackedCollection(count)) {
			targets.fill(1.0, 1.0, 1.0, 1.0, 0.0);
			PoissonInput poisson = new PoissonInput(150e-3, DT, 2.0, targets, step, 7);
			Temporal system = () -> a(cp(total), cp(total).add(poisson.kicks()));
			SimulationRunner runner = new SimulationRunner(system, DT, step);

			runner.run(steps);
			double[] first = total.toArray();
			double kicks = 0;
			for (int i = 0; i < count; i++) {
				if (i % 5 == 4) {
					Assert.assertEquals("untargeted neuron " + i, 0.0, first[i], 0.0);
				} else {
					kicks += first[i] / 2.0;
				}
			}

			double expected = 0.8 * count * steps * 150e-3 * DT;
			double deviation = Math.sqrt(expected * (1 - 150e-3 * DT));
			Assert.assertEquals(expected, kicks, 4 * deviation);

			total.clear();
			runner.reset();
			runner.run(steps);
			Assert.assertArrayEquals(first, total.toArray(), 0.0);

			runner.destroy();
			poisson.destroy();
		}
	}
}
