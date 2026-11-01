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

package org.almostrealism.biology.neuron;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;

import java.util.function.Supplier;

/**
 * A population of leaky integrate-and-fire neurons whose synaptic input decays exponentially,
 * stepped with the exact solution of the coupled membrane and synapse equations. As a
 * {@link Block} it is one stage of a pipe: each step a drive flows in and the population's
 * spikes flow out, so a network step can be composed around it in PDSL or in Java.
 *
 * <p>Each neuron has a membrane potential {@code v} and a synaptic potential {@code g}:</p>
 * <pre>
 *   dv/dt = (v0 - v + g) / tauMembrane
 *   dg/dt = -g / tauSynapse
 * </pre>
 * <p>Both are held while the neuron is refractory, and input that arrives meanwhile is
 * discarded. A neuron spikes when {@code v} rises above
 * the threshold while it is not refractory, and the spike resets {@code v} to the reset
 * potential, clears {@code g}, and starts the neuron's refractory period. This is the model of
 * Shiu et al. (2024), "A Drosophila computational brain model reveals sensorimotor processing",
 * written for the Brian2 simulator, and one step here follows the order of a Brian2 time step:
 * state update, threshold, synaptic and external input, reset.</p>
 *
 * <p>The block's input has {@code 2 * count} values: the first {@code count} are added to the
 * membrane potential (an external kick, such as a Poisson input), and the second {@code count}
 * are added to the synaptic potential (the weighted spikes arriving from the network). One step
 * is a list of element-local assignments over device-resident state:</p>
 * <ol>
 *   <li>The refractory countdown decreases by one, stopping at zero. A neuron whose countdown is
 *       zero is active.</li>
 *   <li>An active neuron advances by the exact propagator of the linear system over one step:
 *       {@code v <- v0 + (v - v0) * dm + g * k} and {@code g <- g * ds}, where
 *       {@code dm = exp(-dt / tauMembrane)}, {@code ds = exp(-dt / tauSynapse)} and
 *       {@code k = tauSynapse / (tauSynapse - tauMembrane) * (ds - dm)}.</li>
 *   <li>An active neuron whose {@code v} exceeds the threshold spikes.</li>
 *   <li>The input of an active neuron is added: the kick to {@code v} and the synaptic input to
 *       {@code g}. A refractory neuron's input is discarded, as Brian2 discards input to a
 *       variable it holds during refractoriness (input arriving on the step of a spike or any of
 *       the held steps after it has no effect).</li>
 *   <li>A neuron that spiked is reset: {@code v} to the reset potential, {@code g} to zero, and its
 *       countdown to its refractory period in steps.</li>
 *   <li>Each spike is added to the neuron's spike count.</li>
 * </ol>
 * <p>The refractory period in steps is computed the way Brian2 converts a duration to steps,
 * {@code (int) ((period + 1e-3 * dt) / dt)}, and a neuron spiking at step {@code s} is held for
 * the steps {@code s + 1} through {@code s + period - 1}. Units are the caller's, as long as they
 * agree: the examples use milliseconds and millivolts.</p>
 *
 * <p>The population owns its state but not the loop that steps it; a {@code SimulationRunner}
 * owns that. {@link #setup()} returns every neuron to rest, with no spikes counted.</p>
 */
public class ExponentialSynapseNeurons implements Block {

	/** The number of neurons. */
	private final int count;

	/** The resting potential {@code v0}. */
	private final double restPotential;

	/** The potential a spike resets the membrane to. */
	private final double resetPotential;

	/** The potential the membrane must exceed to spike. */
	private final double thresholdPotential;

	/** The membrane decay over one step, {@code exp(-dt / tauMembrane)}. */
	private final double membraneDecay;

	/** The synaptic decay over one step, {@code exp(-dt / tauSynapse)}. */
	private final double synapticDecay;

	/** The weight of the synaptic potential in the membrane update over one step. */
	private final double coupling;

	/** The refractory period in whole steps. */
	private final int refractorySteps;

	/** The membrane potential of every neuron. */
	private final PackedCollection membrane;

	/** The synaptic potential of every neuron. */
	private final PackedCollection synapse;

	/** The refractory countdown of every neuron, in steps. */
	private final PackedCollection refractory;

	/** The spikes of the latest step, 1 where a neuron spiked and 0 elsewhere. */
	private final PackedCollection spikes;

	/** The number of spikes of every neuron since the last setup. */
	private final PackedCollection spikeCounts;

	/** The forward cell: one step of the population. */
	private final Cell<PackedCollection> forward;

	/** The backward cell, which does nothing: a step has no gradient. */
	private final Cell<PackedCollection> backward;

	/** 1 for each neuron that has no refractory period, or {@code null} when every neuron has one. */
	private PackedCollection exempt;

	/**
	 * Creates a population at rest.
	 *
	 * @param count              the number of neurons
	 * @param dt                 the duration of one step
	 * @param restPotential      the resting potential {@code v0}
	 * @param resetPotential     the potential a spike resets the membrane to
	 * @param thresholdPotential the potential the membrane must exceed to spike
	 * @param membraneTime       the membrane time constant
	 * @param synapticTime       the synaptic time constant
	 * @param refractoryPeriod   the refractory period, a duration
	 * @throws IllegalArgumentException if the count is not positive or a time is not positive
	 */
	public ExponentialSynapseNeurons(int count, double dt, double restPotential,
									 double resetPotential, double thresholdPotential,
									 double membraneTime, double synapticTime,
									 double refractoryPeriod) {
		if (count <= 0) {
			throw new IllegalArgumentException("A population needs at least one neuron, got " + count);
		}

		if (dt <= 0 || membraneTime <= 0 || synapticTime <= 0 || refractoryPeriod < 0) {
			throw new IllegalArgumentException("Time step and time constants must be positive");
		}

		this.count = count;
		this.restPotential = restPotential;
		this.resetPotential = resetPotential;
		this.thresholdPotential = thresholdPotential;
		this.membraneDecay = Math.exp(-dt / membraneTime);
		this.synapticDecay = Math.exp(-dt / synapticTime);
		this.coupling = membraneTime == synapticTime
				? dt / membraneTime * membraneDecay
				: synapticTime / (synapticTime - membraneTime) * (synapticDecay - membraneDecay);
		this.refractorySteps = (int) ((refractoryPeriod + 1e-3 * dt) / dt);

		this.membrane = new PackedCollection(shape(count));
		this.synapse = new PackedCollection(shape(count));
		this.refractory = new PackedCollection(shape(count));
		this.spikes = new PackedCollection(shape(count));
		this.spikeCounts = new PackedCollection(shape(count));
		this.forward = Cell.of(this::step);
		this.backward = Cell.of((in, next) -> new OperationList("exponential-synapse-neurons-backward"));
	}

	/**
	 * Removes the refractory period of the neurons selected by a mask: they are never held, and a
	 * spike does not stop them spiking on the next step. Brian2 models a neuron driven by a
	 * Poisson input this way. Call this before the step is built.
	 *
	 * @param mask 1 for each neuron without a refractory period and 0 elsewhere, {@code [count]}
	 * @throws IllegalArgumentException if the mask does not have one value per neuron
	 */
	public void exemptFromRefractoryPeriod(PackedCollection mask) {
		if (mask.getShape().getTotalSize() != count) {
			throw new IllegalArgumentException("Mask of " + mask.getShape().getTotalSize()
					+ " values does not match " + count + " neurons");
		}

		this.exempt = mask;
	}

	/**
	 * Builds one step of the population for a given input.
	 *
	 * @param input producer of the {@code 2 * count} input values: membrane kicks, then synaptic
	 *              input
	 * @param next  the receptor the spike vector is pushed to, or {@code null} for none
	 * @return the ordered assignments of one step
	 */
	protected Supplier<Runnable> step(Producer<PackedCollection> input, Receptor<PackedCollection> next) {
		CollectionProducer flat = c(input).reshape(shape(2 * count));
		CollectionProducer kick = subset(shape(count), flat, 0);
		CollectionProducer synaptic = subset(shape(count), flat, count);

		OperationList op = new OperationList("exponential-synapse-neurons");
		op.add(a("refractory-countdown", cp(refractory), max(cp(refractory).subtract(c(1.0)), c(0.0))));
		op.add(a("membrane-integration", cp(membrane), cp(membrane)
				.add(active().multiply(integratedMembrane().subtract(cp(membrane))))));
		op.add(a("synapse-integration", cp(synapse), cp(synapse)
				.multiply(c(1.0).add(active().multiply(c(synapticDecay - 1.0))))));
		op.add(a("threshold", cp(spikes),
				greaterThan(cp(membrane), c(thresholdPotential), active(), c(0.0))));
		op.add(a("membrane-reset", cp(membrane),
				resetWhereSpiked(cp(membrane).add(active().multiply(kick)), c(resetPotential))));
		op.add(a("synapse-reset", cp(synapse),
				resetWhereSpiked(cp(synapse).add(active().multiply(synaptic)), c(0.0))));
		op.add(a("refractory-reload", cp(refractory),
				resetWhereSpiked(cp(refractory), refractoryPeriods())));
		op.add(a("spike-count", cp(spikeCounts), cp(spikeCounts).add(cp(spikes))));

		if (next != null) {
			op.add(next.push(p(spikes)));
		}

		return op;
	}

	/**
	 * Returns 1 for each neuron whose refractory countdown is zero and 0 elsewhere.
	 *
	 * @return a producer of the {@code [count]} activity mask
	 */
	protected CollectionProducer active() {
		return lessThan(cp(refractory), c(0.5), c(1.0), c(0.0));
	}

	/**
	 * Returns the membrane potential one step ahead under the exact propagator, from the current
	 * membrane and synaptic potentials.
	 *
	 * @return a producer of the {@code [count]} advanced membrane potential
	 */
	protected CollectionProducer integratedMembrane() {
		return c(restPotential)
				.add(cp(membrane).subtract(c(restPotential)).multiply(c(membraneDecay)))
				.add(cp(synapse).multiply(c(coupling)));
	}

	/**
	 * Returns the refractory period of every neuron in steps: the population's period, or zero for
	 * an exempt neuron.
	 *
	 * @return a producer of the per-neuron refractory period
	 */
	protected CollectionProducer refractoryPeriods() {
		CollectionProducer period = c((double) refractorySteps);
		return exempt == null ? period : period.multiply(c(1.0).subtract(cp(exempt)));
	}

	/**
	 * Selects the reset value where a neuron spiked in this step and the given value elsewhere.
	 *
	 * @param value the value of a neuron that did not spike
	 * @param reset the value of a neuron that spiked
	 * @return a producer of the {@code [count]} selection
	 */
	protected CollectionProducer resetWhereSpiked(CollectionProducer value, CollectionProducer reset) {
		return value.add(cp(spikes).multiply(reset.subtract(value)));
	}

	/**
	 * Returns every neuron to rest: the membrane at the resting potential, and the synaptic
	 * potential, refractory countdown, spikes and spike counts at zero.
	 *
	 * @return the assignments of the reset
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("exponential-synapse-neurons-setup");
		setup.add(a(cp(membrane).each(), c(restPotential)));
		setup.add(a(cp(synapse).each(), c(0.0)));
		setup.add(a(cp(refractory).each(), c(0.0)));
		setup.add(a(cp(spikes).each(), c(0.0)));
		setup.add(a(cp(spikeCounts).each(), c(0.0)));
		return setup;
	}

	/**
	 * Returns the input shape, {@code [2 * count]}: membrane kicks, then synaptic input.
	 *
	 * @return the input shape, {@code [2 * count]}: membrane kicks, then synaptic input
	 */
	@Override
	public TraversalPolicy getInputShape() { return shape(2 * count); }

	/**
	 * Returns the output shape, {@code [count]}: the spikes of the step.
	 *
	 * @return the output shape, {@code [count]}: the spikes of the step
	 */
	@Override
	public TraversalPolicy getOutputShape() { return shape(count); }

	/**
	 * Returns the cell that performs one step and pushes the spikes on.
	 *
	 * @return the cell that performs one step and pushes the spikes on
	 */
	@Override
	public Cell<PackedCollection> getForward() { return forward; }

	/**
	 * Returns the backward cell, which does nothing.
	 *
	 * @return the backward cell, which does nothing
	 */
	@Override
	public Cell<PackedCollection> getBackward() { return backward; }

	/**
	 * Returns the number of neurons.
	 *
	 * @return the number of neurons
	 */
	public int getCount() { return count; }

	/**
	 * Returns the refractory period in whole steps.
	 *
	 * @return the refractory period in whole steps
	 */
	public int getRefractorySteps() { return refractorySteps; }

	/**
	 * Returns the membrane potential of every neuron.
	 *
	 * @return the membrane potential of every neuron
	 */
	public PackedCollection getMembrane() { return membrane; }

	/**
	 * Returns the synaptic potential of every neuron.
	 *
	 * @return the synaptic potential of every neuron
	 */
	public PackedCollection getSynapse() { return synapse; }

	/**
	 * Returns the refractory countdown of every neuron, in steps.
	 *
	 * @return the refractory countdown of every neuron, in steps
	 */
	public PackedCollection getRefractory() { return refractory; }

	/**
	 * Returns the spikes of the latest step.
	 *
	 * @return the spikes of the latest step
	 */
	public PackedCollection getSpikes() { return spikes; }

	/**
	 * Returns the number of spikes of every neuron since the last setup.
	 *
	 * @return the number of spikes of every neuron since the last setup
	 */
	public PackedCollection getSpikeCounts() { return spikeCounts; }

	/** Releases the state collections. The exemption mask belongs to the caller. */
	@Override
	public void destroy() {
		membrane.destroy();
		synapse.destroy();
		refractory.destroy();
		spikes.destroy();
		spikeCounts.destroy();
	}
}
