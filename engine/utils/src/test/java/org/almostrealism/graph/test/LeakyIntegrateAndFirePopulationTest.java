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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.LeakyIntegrateAndFirePopulation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link LeakyIntegrateAndFirePopulation}, the leaky integrate-and-fire spiking
 * population whose one-step dynamics are {@link org.almostrealism.collect.CollectionProducer}
 * math over persistent device-resident state.
 *
 * <p>The tests cover the success criteria the dynamics can be proven against on the native
 * backend: that the membrane update is exact exponential producer math (compiled to a kernel), that
 * a neuron under constant input fires at the analytic LIF rate, that the absolute refractory period
 * floors the inter-spike interval, that a presynaptic spike reaches its target exactly
 * {@code delaySteps} later through the ring buffer, and that the Poisson drive is a reproducible
 * in-kernel draw at the configured rate. Spike-for-spike parity against a Brian2 reference raster is
 * gated on that reference being supplied and is not exercised here.</p>
 */
public class LeakyIntegrateAndFirePopulationTest extends TestSuiteBase {
	/** The simulation time step used by the tests. */
	private static final double DT = 0.1;

	/** The membrane time constant used by the tests. */
	private static final double TAU_M = 20.0;

	/** The synaptic time constant used by the tests. */
	private static final double TAU_SYN = 5.0;

	/** The per-step synaptic conductance decay, matching the population's internal factor. */
	private static final double SYNAPTIC_DECAY = Math.exp(-DT / TAU_SYN);

	/**
	 * Runs a compiled step the given number of times, recording the step indices at which the given
	 * neuron emits a spike.
	 *
	 * @param step   the compiled one-step operation
	 * @param spikes the population's spike vector, read after each step
	 * @param neuron the neuron whose spikes are recorded
	 * @param steps  the number of steps to run
	 * @return the step indices at which the neuron fired
	 */
	private List<Integer> spikeSteps(Runnable step, PackedCollection spikes, int neuron, int steps) {
		List<Integer> fired = new ArrayList<>();
		for (int t = 0; t < steps; t++) {
			step.run();
			if (spikes.toDouble(neuron) >= 0.5) {
				fired.add(t);
			}
		}
		return fired;
	}

	/**
	 * A current held constant across a step injected into the conductance so that, with the
	 * conductance preloaded to its steady state, the conductance stays fixed and the membrane sees a
	 * constant drive.
	 *
	 * @param steadyState the steady-state conductance (and, with unit resistance, the drive)
	 * @return the per-step input that keeps the conductance at {@code steadyState}
	 */
	private double constantInput(double steadyState) {
		return steadyState * (1.0 - SYNAPTIC_DECAY);
	}

	/**
	 * The membrane update is the exact exponential of the time step: under a constant drive and with
	 * spiking disabled, the membrane follows {@code steadyState * (1 - exp(-t / tauM))} to within a
	 * tight numerical tolerance, which it could only do as compiled producer math.
	 */
	@Test(timeout = 60000)
	public void membraneFollowsExactExponential() {
		double drive = 2.0;
		LeakyIntegrateAndFirePopulation pop = new LeakyIntegrateAndFirePopulation(
				1, DT, TAU_M, TAU_SYN, 0.0, 0.0, 1e9, 1.0, 0, 1);

		pop.getConductance().fill(drive);

		Runnable step = pop.step(c(constantInput(drive))).get();

		double membraneDecay = Math.exp(-DT / TAU_M);
		for (int k = 1; k <= 50; k++) {
			step.run();
			double expected = drive * (1.0 - Math.pow(membraneDecay, k));
			Assert.assertEquals("step " + k, expected, pop.getMembrane().toDouble(0), 1e-5);
		}

		pop.destroy();
	}

	/**
	 * A single neuron under constant input current fires at the analytic LIF rate
	 * {@code r = 1 / (t_ref + tauM * ln((R*I - V_reset) / (R*I - V_th)))} across a range of currents,
	 * and a larger current gives a higher rate.
	 */
	@Test(timeout = 120000)
	public void analyticFiringRate() {
		double threshold = 1.0;
		double tRef = 2.0;
		int refractorySteps = (int) Math.round(tRef / DT);
		int steps = 10000;

		double previousRate = 0.0;
		for (double drive : new double[] { 2.0, 4.0 }) {
			LeakyIntegrateAndFirePopulation pop = new LeakyIntegrateAndFirePopulation(
					1, DT, TAU_M, TAU_SYN, 0.0, 0.0, threshold, 1.0, refractorySteps, 1);

			pop.getConductance().fill(drive);

			Runnable step = pop.step(c(constantInput(drive))).get();
			int spikes = spikeSteps(step, pop.getSpikes(), 0, steps).size();

			double measured = spikes / (steps * DT);
			double analytic = 1.0 / (tRef + TAU_M * Math.log((drive) / (drive - threshold)));
			log("drive=" + drive + " measuredRate=" + measured + " analyticRate=" + analytic);
			Assert.assertEquals(analytic, measured, 0.06 * analytic);
			Assert.assertTrue(measured > previousRate);
			previousRate = measured;

			pop.destroy();
		}
	}

	/**
	 * A neuron driven hard enough to cross threshold every step it is active cannot spike again until
	 * the absolute refractory period has elapsed, so its inter-spike interval is floored at
	 * {@code refractorySteps + 1} steps, while the same drive with no refractory period fires every
	 * step.
	 */
	@Test(timeout = 60000)
	public void refractoryPeriodFloorsInterSpikeInterval() {
		double drive = 1000.0;
		int refractorySteps = 20;
		int steps = 200;

		List<Integer> refractoryFiring = firingUnderConstantDrive(drive, refractorySteps, steps);
		List<Integer> freeFiring = firingUnderConstantDrive(drive, 0, steps);

		Assert.assertTrue(refractoryFiring.size() > 1);
		for (int i = 1; i < refractoryFiring.size(); i++) {
			int interval = refractoryFiring.get(i) - refractoryFiring.get(i - 1);
			Assert.assertEquals(refractorySteps + 1, interval);
		}

		for (int i = 1; i < freeFiring.size(); i++) {
			Assert.assertEquals(1, (int) (freeFiring.get(i) - freeFiring.get(i - 1)));
		}

		Assert.assertTrue(freeFiring.size() > refractoryFiring.size());
	}

	/**
	 * Drives one neuron at a constant suprathreshold current and returns the steps at which it fires.
	 *
	 * @param drive           the constant membrane drive
	 * @param refractorySteps the absolute refractory period in steps
	 * @param steps           the number of steps to run
	 * @return the step indices at which the neuron fired
	 */
	private List<Integer> firingUnderConstantDrive(double drive, int refractorySteps, int steps) {
		LeakyIntegrateAndFirePopulation pop = new LeakyIntegrateAndFirePopulation(
				1, DT, TAU_M, TAU_SYN, 0.0, 0.0, 1.0, 1.0, refractorySteps, 1);

		pop.getConductance().fill(drive);

		Runnable step = pop.step(c(constantInput(drive))).get();
		List<Integer> fired = spikeSteps(step, pop.getSpikes(), 0, steps);

		pop.destroy();
		return fired;
	}

	/**
	 * The delayed spike vector read from the ring buffer reflects a spike exactly {@code delaySteps}
	 * steps after it was emitted, and not before: a single spike by neuron 0 at step 0 appears in
	 * {@link LeakyIntegrateAndFirePopulation#delayedSpikes()} only at step {@code delaySteps}.
	 */
	@Test(timeout = 60000)
	public void ringBufferDelaysSpikeExactly() {
		int delaySteps = 5;
		LeakyIntegrateAndFirePopulation pop = new LeakyIntegrateAndFirePopulation(
				2, DT, TAU_M, TAU_SYN, 0.0, 0.0, 1.0, 1.0, 0, delaySteps);

		PackedCollection zeros = new PackedCollection(shape(2));
		pop.getMembrane().setMem(0, 10.0);

		Evaluable<PackedCollection> delayed = pop.delayedSpikes().get();
		Runnable step = pop.step(cp(zeros)).get();

		for (int t = 0; t <= delaySteps; t++) {
			double value = delayed.evaluate().toDouble(0);
			if (t < delaySteps) {
				Assert.assertEquals("step " + t, 0.0, value, 0.0);
			} else {
				Assert.assertEquals("step " + t, 1.0, value, 0.0);
			}
			step.run();
		}

		pop.destroy();
		zeros.destroy();
	}

	/**
	 * A presynaptic spike reaches its postsynaptic target through the weighted ring read exactly
	 * {@code delaySteps} later: with a connection of weight {@code w} from neuron 0 to neuron 1,
	 * neuron 0's one spike at step 0 raises neuron 1's conductance to {@code w} at step
	 * {@code delaySteps}, and leaves it at zero at every earlier step.
	 */
	@Test(timeout = 60000)
	public void synapticDelayDeliversWeightedInputAfterDelay() {
		int delaySteps = 5;
		double weight = 5.0;
		LeakyIntegrateAndFirePopulation pop = new LeakyIntegrateAndFirePopulation(
				2, DT, TAU_M, TAU_SYN, 0.0, 0.0, 1.0, 1.0, 0, delaySteps);

		PackedCollection weights = PackedCollection.of(0.0, 0.0, weight, 0.0).reshape(shape(2, 2));
		pop.getMembrane().setMem(0, 10.0);

		Producer<PackedCollection> synapticInput = matmul(cp(weights), pop.delayedSpikes());
		Runnable step = pop.step(synapticInput).get();

		for (int t = 0; t <= delaySteps; t++) {
			step.run();
			double conductance = pop.getConductance().toDouble(1);
			if (t < delaySteps) {
				Assert.assertEquals("step " + t, 0.0, conductance, 0.0);
			} else {
				Assert.assertEquals("step " + t, weight, conductance, 1e-6);
			}
		}

		pop.destroy();
		weights.destroy();
	}

	/**
	 * The Poisson drive fires a neuron, in the kernel, when its uniform draw falls below
	 * {@code rate * dt}: the measured fraction of driven steps matches {@code rate * dt}, and the draw
	 * changes step to step as the counter advances.
	 */
	@Test(timeout = 120000)
	public void poissonDriveMatchesRate() {
		int count = 256;
		double rate = 0.1;
		int steps = 2000;
		double expectedFraction = rate * DT;

		LeakyIntegrateAndFirePopulation pop = poissonPopulation(count, rate, 7);

		PackedCollection zeros = new PackedCollection(shape(count));
		PackedCollection total = new PackedCollection(shape(count));

		OperationList full = new OperationList("lif-step-count");
		full.addAll(pop.step(cp(zeros)));
		full.add(a(cp(total), add(cp(total), cp(pop.getSpikes()))));
		Runnable step = full.get();

		boolean sawDifference = false;
		double[] previous = null;
		for (int t = 0; t < steps; t++) {
			step.run();
			double[] current = pop.getSpikes().toArray();
			if (previous != null && !sawDifference) {
				for (int i = 0; i < count; i++) {
					if (current[i] != previous[i]) {
						sawDifference = true;
						break;
					}
				}
			}
			previous = current;
		}

		double spikeTotal = 0.0;
		for (double v : total.toArray()) spikeTotal += v;
		double measuredFraction = spikeTotal / ((double) count * steps);
		log("poisson measuredFraction=" + measuredFraction + " expected=" + expectedFraction);

		Assert.assertEquals(expectedFraction, measuredFraction, 0.05 * expectedFraction);
		Assert.assertTrue("Poisson draw did not vary across steps", sawDifference);

		pop.destroy();
		zeros.destroy();
		total.destroy();
	}

	/**
	 * The Poisson drive is reproducible across backend-independent runs: two populations seeded
	 * alike emit the same spike train step for step, while a population with a different seed does
	 * not.
	 */
	@Test(timeout = 60000)
	public void poissonDriveIsReproducible() {
		int count = 128;
		double rate = 0.2;
		int steps = 40;

		LeakyIntegrateAndFirePopulation a = poissonPopulation(count, rate, 7);
		LeakyIntegrateAndFirePopulation b = poissonPopulation(count, rate, 7);
		LeakyIntegrateAndFirePopulation c = poissonPopulation(count, rate, 8);

		PackedCollection zeros = new PackedCollection(shape(count));
		Runnable stepA = a.step(cp(zeros)).get();
		Runnable stepB = b.step(cp(zeros)).get();
		Runnable stepC = c.step(cp(zeros)).get();

		boolean differsFromC = false;
		for (int t = 0; t < steps; t++) {
			stepA.run();
			stepB.run();
			stepC.run();

			double[] av = a.getSpikes().toArray();
			Assert.assertArrayEquals("step " + t, av, b.getSpikes().toArray(), 0.0);

			if (!differsFromC) {
				double[] cv = c.getSpikes().toArray();
				for (int i = 0; i < count; i++) {
					if (av[i] != cv[i]) {
						differsFromC = true;
						break;
					}
				}
			}
		}

		Assert.assertTrue("A different seed produced an identical spike train", differsFromC);

		a.destroy();
		b.destroy();
		c.destroy();
		zeros.destroy();
	}

	/**
	 * Creates a population with a Poisson drive whose membrane never crosses threshold on its own, so
	 * that every emitted spike comes from the drive.
	 *
	 * @param count the neuron count
	 * @param rate  the Poisson rate
	 * @param seed  the generator seed
	 * @return the configured population
	 */
	private LeakyIntegrateAndFirePopulation poissonPopulation(int count, double rate, int seed) {
		LeakyIntegrateAndFirePopulation pop = new LeakyIntegrateAndFirePopulation(
				count, DT, TAU_M, TAU_SYN, 0.0, 0.0, 1.0, 0.0, 0, 1);
		pop.setPoissonDrive(rate, null, seed);
		pop.reset();
		return pop;
	}
}
