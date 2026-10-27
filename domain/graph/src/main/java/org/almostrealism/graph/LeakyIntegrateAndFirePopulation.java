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

package org.almostrealism.graph;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionSlotUpdateComputation;
import org.almostrealism.collect.computations.CounterRandom;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;

import java.util.ArrayList;
import java.util.List;

/**
 * A population of leaky integrate-and-fire (LIF) neurons whose one-step forward dynamics are
 * expressed entirely as {@link CollectionProducer} math over persistent, device-resident state.
 *
 * <p>This is the forward dynamical rule each neuron follows every time step of a spiking
 * simulation. One step of the whole population is a short list of compiled operations over the
 * state collections, with no Java arithmetic and no per-element host access, so it compiles once
 * and re-dispatches per step on every backend. The class owns the state and builds the step; it
 * does not own the loop that drives the step &mdash; that is the responsibility of a separate
 * simulation-loop owner, exactly as {@code ModelOptimizer} owns training and
 * {@code DiffusionSampler} owns sampling.</p>
 *
 * <h2>State</h2>
 * <p>The population of {@code count} neurons holds, device-resident and persistent across steps:
 * the membrane potential {@code v[count]}, the synaptic conductance {@code g[count]}, a refractory
 * countdown {@code refractory[count]} (in whole steps), the emitted spike vector
 * {@code spike[count]} (1 where a neuron fired this step, 0 elsewhere), a spike ring buffer
 * {@code ring[rows, count]}, and the ring's dynamic write index (a single-element collection). The
 * ring has {@code delaySteps + 1} rows so that the row read for the delayed spike vector is never
 * the row being overwritten this step (see {@link #delayedSpikes()}).</p>
 *
 * <h2>The step</h2>
 * <p>{@link #step(Producer)} returns the one-step dynamics as an ordered {@link OperationList} of
 * assignments, each reading the state the previous assignment left (the same device-side sequencing
 * {@code AutoregressiveModel} uses for its position advance). The caller compiles it once with
 * {@link OperationList#get()} and runs the resulting operation every step, in this order:</p>
 * <ol>
 *   <li>the conductance decays by the exact exponential {@code g * exp(-dt / tauSyn)} and
 *       accumulates the step's synaptic input;</li>
 *   <li>the membrane relaxes toward its steady state {@code vRest + resistance * g} by the exact
 *       exponential {@code exp(-dt / tauM)} while the neuron is active, and is held at
 *       {@code resetPotential} while it is refractory;</li>
 *   <li>a spike is detected where the membrane reaches {@code thresholdPotential}, combined (by
 *       logical or) with the Poisson drive on the input set when one is configured;</li>
 *   <li>the membrane is reset to {@code resetPotential} where a spike occurred;</li>
 *   <li>the refractory countdown is reloaded to {@code refractorySteps} where a spike occurred and
 *       decremented (floored at zero) elsewhere;</li>
 *   <li>the spike vector is written into the ring at the write index, and the write index is
 *       advanced modulo the number of rows;</li>
 *   <li>the Poisson generator's counter is advanced, when a drive is configured.</li>
 * </ol>
 *
 * <p>The membrane and conductance both integrate by the <em>exact exponential</em> of the time
 * step: for the linear LIF equations with input held constant across a step this is the exact
 * solution sampled at step boundaries, so between spikes the membrane follows the continuous
 * trajectory and a neuron under constant input current fires at the analytic LIF rate
 * {@code r = 1 / (t_ref + tauM * ln((R*I - V_reset) / (R*I - V_th)))}.</p>
 *
 * <h2>Synaptic input and delay</h2>
 * <p>The synaptic input of a step enters {@link #step(Producer)} as a
 * {@code Producer<PackedCollection>} argument, so the same step works with a dense weight-times-spike
 * product (the small tests here) and with a sparse one later, without change. The caller forms that
 * product from {@link #delayedSpikes()}, the spike vector emitted {@code delaySteps} steps ago read
 * back out of the ring, and the connectivity weights. This dual data flow &mdash; emit the delayed
 * spike vector, consume the resulting synaptic input &mdash; is why the population is a stateful
 * owner class rather than a single-signal {@code Cell} or {@code Block}.</p>
 *
 * <h2>Poisson drive</h2>
 * <p>An optional Poisson drive forces the neurons of an input set to spike when their uniform draw
 * falls below {@code rate * dt}. The draw comes from the in-kernel counter-based generator
 * {@link CounterRandom}, a pure function of {@code (seed, counter, index)}, so the drive is
 * reproducible and bit-identical across backends for a fixed seed. {@link #step(Producer)} advances
 * the generator's counter device-side at the end of each step.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>The population owns its state collections and the compiled reset operation, and
 * {@link #destroy()} releases them together with the Poisson generator when one is configured. A
 * step operation built by {@link #step(Producer)} reads the state collections, so the caller
 * releases that compiled operation before destroying the population.</p>
 *
 * @author Michael Murray
 */
public class LeakyIntegrateAndFirePopulation implements LayerFeatures, Destroyable {

	/** The number of neurons in the population. */
	private final int count;

	/** The simulation time step. */
	private final double dt;

	/** The resting membrane potential the membrane leaks toward in the absence of input. */
	private final double restPotential;

	/** The potential the membrane is reset to after a spike and held at while refractory. */
	private final double resetPotential;

	/** The threshold potential at or above which a neuron spikes. */
	private final double thresholdPotential;

	/** The membrane resistance scaling the conductance-driven current. */
	private final double resistance;

	/** The number of whole steps a neuron is held at reset after spiking (absolute refractory). */
	private final int refractorySteps;

	/** The synaptic delay in whole steps, the latency from a spike to its effect on a target. */
	private final int delaySteps;

	/** The number of rows in the spike ring buffer, {@code delaySteps + 1}. */
	private final int rows;

	/** The per-step membrane decay factor {@code exp(-dt / tauM)}. */
	private final double membraneDecay;

	/** The per-step synaptic conductance decay factor {@code exp(-dt / tauSyn)}. */
	private final double synapticDecay;

	/** The membrane potential of each neuron, {@code [count]}. */
	private final PackedCollection membrane;

	/** The synaptic conductance of each neuron, {@code [count]}. */
	private final PackedCollection conductance;

	/** The refractory countdown of each neuron in whole steps, {@code [count]}. */
	private final PackedCollection refractory;

	/** The spike vector emitted this step, {@code [count]}, 1 where a neuron fired and 0 elsewhere. */
	private final PackedCollection spikes;

	/** The spike ring buffer, {@code [rows, count]}, one recent spike vector per row. */
	private final PackedCollection ring;

	/** The ring's write index, a single-element collection advanced each step. */
	private final PackedCollection writeIndex;

	/** The Poisson uniform draw {@code rate * dt} threshold, or {@code null} when no drive is configured. */
	private Double poissonThreshold;

	/** The counter-based generator producing the Poisson uniform draws, or {@code null} when unused. */
	private CounterRandom poisson;

	/** The input-set mask, {@code [count]}, selecting which neurons the Poisson drive applies to, or {@code null} for all. */
	private PackedCollection inputMask;

	/** The compiled operation returning all state to its initial values, built once by {@link #reset()}. */
	private Runnable reset;

	/**
	 * Creates a population of leaky integrate-and-fire neurons with no Poisson drive.
	 *
	 * @param count              the number of neurons
	 * @param dt                 the simulation time step
	 * @param membraneTimeConstant the membrane time constant {@code tauM}
	 * @param synapticTimeConstant the synaptic time constant {@code tauSyn}
	 * @param restPotential      the resting membrane potential
	 * @param resetPotential     the potential the membrane is reset to and held at while refractory
	 * @param thresholdPotential the threshold potential at or above which a neuron spikes
	 * @param resistance         the membrane resistance scaling the conductance-driven current
	 * @param refractorySteps    the whole-step absolute refractory period (zero for none)
	 * @param delaySteps         the synaptic delay in whole steps, at least one
	 * @throws IllegalArgumentException if a count, time constant or step count is out of range
	 */
	public LeakyIntegrateAndFirePopulation(int count, double dt,
										   double membraneTimeConstant, double synapticTimeConstant,
										   double restPotential, double resetPotential,
										   double thresholdPotential, double resistance,
										   int refractorySteps, int delaySteps) {
		if (count < 1) {
			throw new IllegalArgumentException("count must be positive");
		} else if (dt <= 0.0) {
			throw new IllegalArgumentException("dt must be positive");
		} else if (membraneTimeConstant <= 0.0 || synapticTimeConstant <= 0.0) {
			throw new IllegalArgumentException("time constants must be positive");
		} else if (refractorySteps < 0) {
			throw new IllegalArgumentException("refractorySteps must not be negative");
		} else if (delaySteps < 1) {
			throw new IllegalArgumentException("delaySteps must be at least one");
		}

		this.count = count;
		this.dt = dt;
		this.restPotential = restPotential;
		this.resetPotential = resetPotential;
		this.thresholdPotential = thresholdPotential;
		this.resistance = resistance;
		this.refractorySteps = refractorySteps;
		this.delaySteps = delaySteps;
		this.rows = delaySteps + 1;
		this.membraneDecay = Math.exp(-dt / membraneTimeConstant);
		this.synapticDecay = Math.exp(-dt / synapticTimeConstant);

		this.membrane = new PackedCollection(shape(count));
		this.conductance = new PackedCollection(shape(count));
		this.refractory = new PackedCollection(shape(count));
		this.spikes = new PackedCollection(shape(count));
		this.ring = new PackedCollection(shape(rows, count));
		this.writeIndex = new PackedCollection(shape(1));

		reset();
	}

	/**
	 * Configures the Poisson drive: each step, every neuron selected by {@code mask} spikes when its
	 * uniform draw falls below {@code rate * dt}. The draws come from a counter-based generator seeded
	 * with {@code seed}, so the drive is reproducible and bit-identical across backends.
	 *
	 * @param rate the Poisson firing rate of a driven neuron (spikes per unit time)
	 * @param mask the input-set mask, {@code [count]} with 1 on driven neurons, or {@code null} for all
	 * @param seed the generator seed, in {@code [0, }{@link CounterRandom#SEED_LIMIT}{@code )}
	 * @throws IllegalArgumentException if {@code rate} is negative or {@code mask} is the wrong shape
	 */
	public void setPoissonDrive(double rate, PackedCollection mask, int seed) {
		if (rate < 0.0) {
			throw new IllegalArgumentException("rate must not be negative");
		} else if (mask != null && mask.getShape().getTotalSize() != count) {
			throw new IllegalArgumentException("mask must hold one value per neuron (" + count + ")");
		}

		this.poissonThreshold = rate * dt;
		this.poisson = new CounterRandom(shape(count), false, seed);
		this.inputMask = mask;

		if (reset != null) {
			Destroyable.destroy(reset);
			reset = null;
		}
	}

	/**
	 * Returns the one step of the population's dynamics as an ordered operation list. The caller
	 * compiles it once with {@link OperationList#get()} and runs the result every step; the
	 * operations read the population's state and leave it advanced by one step. The order of the
	 * assignments, and the quantities each computes, are described in the class documentation.
	 *
	 * @param synapticInput the synaptic input current of this step, a producer of {@code [count]}
	 *                      values, typically the product of the connectivity weights and
	 *                      {@link #delayedSpikes()}
	 * @return the ordered assignments implementing one step
	 */
	public OperationList step(Producer<PackedCollection> synapticInput) {
		OperationList op = new OperationList("lif-step");

		op.add(a(cp(conductance),
				add(multiply(cp(conductance), c(synapticDecay)), synapticInput)));

		CollectionProducer active = greaterThan(cp(refractory), c(0.0), c(0.0), c(1.0));
		CollectionProducer steadyState = add(c(restPotential), multiply(c(resistance), cp(conductance)));
		CollectionProducer integrated = add(steadyState,
				multiply(subtract(cp(membrane), steadyState), c(membraneDecay)));
		op.add(a(cp(membrane),
				add(multiply(active, integrated),
						multiply(subtract(c(1.0), active), c(resetPotential)))));

		op.add(a(cp(spikes), emittedSpikes()));

		op.add(a(cp(membrane),
				add(multiply(cp(spikes), c(resetPotential)),
						multiply(subtract(c(1.0), cp(spikes)), cp(membrane)))));

		op.add(a(cp(refractory),
				add(multiply(cp(spikes), c((double) refractorySteps)),
						multiply(subtract(c(1.0), cp(spikes)),
								rectify(subtract(cp(refractory), c(1.0)))))));

		CollectionProducer offset = multiply(cp(writeIndex), c((double) count));
		op.add(into("lif-ring-write",
				new CollectionSlotUpdateComputation(shape(rows, count), 1, rows * count, count,
						cp(ring), cp(spikes), offset),
				cp(ring), false));

		op.add(a(cp(writeIndex), mod(add(cp(writeIndex), c(1.0)), c((double) rows))));

		if (poisson != null) {
			op.add(poisson.advance());
		}

		return op;
	}

	/**
	 * Builds the emitted spike vector of this step: a threshold crossing, combined by logical or with
	 * the Poisson drive on the input set when a drive is configured.
	 *
	 * @return a producer of the {@code [count]} spike vector, 1 where a neuron fires and 0 elsewhere
	 */
	private CollectionProducer emittedSpikes() {
		CollectionProducer threshold = greaterThanOrEqual(cp(membrane), c(thresholdPotential));
		if (poisson == null) {
			return threshold;
		}

		CollectionProducer drive = lessThan(poisson, c(poissonThreshold), c(1.0), c(0.0));
		if (inputMask != null) {
			drive = multiply(drive, cp(inputMask));
		}

		return max(threshold, drive);
	}

	/**
	 * Returns the spike vector emitted {@code delaySteps} steps ago, read from the ring buffer. The
	 * caller multiplies this by the connectivity weights to form the synaptic input of the current
	 * step. The row read is {@code (writeIndex - delaySteps) mod rows}, which with
	 * {@code rows = delaySteps + 1} is never the row {@link #step(Producer)} overwrites this step, so
	 * the delay is exactly {@code delaySteps} regardless of the order of the read and the write.
	 *
	 * @return a producer of the {@code [count]} delayed spike vector
	 */
	public CollectionProducer delayedSpikes() {
		CollectionProducer rowPosition = mod(
				add(cp(writeIndex), c((double) (rows - delaySteps))), c((double) rows));
		CollectionProducer index = integers(0, count)
				.add(multiply(rowPosition, c((double) count)));
		return c(shape(count), cp(ring).reshape(shape(rows * count)), index);
	}

	/**
	 * Returns all state to its initial values on the device: the membrane to its resting potential,
	 * the conductance, refractory countdown, spike vector, ring buffer and write index to zero, and
	 * the Poisson counter to zero. The reset is a compiled operation built once and reused.
	 */
	public final void reset() {
		if (reset == null) {
			OperationList op = new OperationList("lif-reset");
			op.add(a(cp(membrane), add(multiply(cp(membrane), c(0.0)), c(restPotential))));
			op.add(a(cp(conductance), multiply(cp(conductance), c(0.0))));
			op.add(a(cp(refractory), multiply(cp(refractory), c(0.0))));
			op.add(a(cp(spikes), multiply(cp(spikes), c(0.0))));
			op.add(a(cp(ring), multiply(cp(ring), c(0.0))));
			op.add(a(cp(writeIndex), c(0.0)));
			if (poisson != null) {
				op.add(poisson.resetCounter());
			}
			reset = op.get();
		}

		reset.run();
	}

	/**
	 * Returns the number of neurons in the population.
	 *
	 * @return the neuron count
	 */
	public int getCount() { return count; }

	/**
	 * Returns the synaptic delay in whole steps.
	 *
	 * @return the delay in steps
	 */
	public int getDelaySteps() { return delaySteps; }

	/**
	 * Returns the number of rows in the spike ring buffer, {@code delaySteps + 1}.
	 *
	 * @return the ring row count
	 */
	public int getRows() { return rows; }

	/**
	 * Returns the membrane potential collection, {@code [count]}.
	 *
	 * @return the membrane potentials
	 */
	public PackedCollection getMembrane() { return membrane; }

	/**
	 * Returns the synaptic conductance collection, {@code [count]}.
	 *
	 * @return the conductances
	 */
	public PackedCollection getConductance() { return conductance; }

	/**
	 * Returns the refractory countdown collection in whole steps, {@code [count]}.
	 *
	 * @return the refractory countdowns
	 */
	public PackedCollection getRefractory() { return refractory; }

	/**
	 * Returns the spike vector emitted by the most recent step, {@code [count]}.
	 *
	 * @return the spike vector
	 */
	public PackedCollection getSpikes() { return spikes; }

	/**
	 * Returns the spike ring buffer, {@code [rows, count]}.
	 *
	 * @return the ring buffer
	 */
	public PackedCollection getRing() { return ring; }

	/**
	 * Returns the ring's write index, a single-element collection.
	 *
	 * @return the write index
	 */
	public PackedCollection getWriteIndex() { return writeIndex; }

	/**
	 * Releases the state collections, the compiled reset operation and the Poisson generator. A step
	 * operation built by {@link #step(Producer)} must be released by the caller before this is called,
	 * since it reads the state collections.
	 */
	@Override
	public void destroy() {
		List<Runnable> releases = new ArrayList<>();
		releases.add(() -> Destroyable.destroy(reset));
		releases.add(membrane::destroy);
		releases.add(conductance::destroy);
		releases.add(refractory::destroy);
		releases.add(spikes::destroy);
		releases.add(ring::destroy);
		releases.add(writeIndex::destroy);
		if (poisson != null) {
			releases.add(poisson::destroy);
		}
		Destroyable.releaseAll(releases);
	}
}
