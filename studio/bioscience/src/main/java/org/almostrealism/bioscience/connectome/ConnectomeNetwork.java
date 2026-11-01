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

package org.almostrealism.bioscience.connectome;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.lifecycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.biology.neuron.ExponentialSynapseNeurons;
import org.almostrealism.biology.neuron.PoissonInput;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CounterRandom;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.dsl.PdslNode;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A spiking network over a {@link NeuronConnectome}: every neuron an
 * {@link ExponentialSynapseNeurons} neuron, every synapse a delayed, weighted connection, and a
 * selected set of neurons driven by Poisson inputs. With the default constants this is the
 * whole-brain model of Shiu et al. (2024), "A Drosophila computational brain model reveals
 * sensorimotor processing", which its authors simulate with Brian2.
 *
 * <p>One time step is the PDSL layer {@code connectome_step} in {@code /pdsl/connectome.pdsl}. An
 * external drive flows in, one value per neuron, added to the membrane potential; it is zero
 * unless something outside the network stimulates it. The Poisson input adds its kicks to the
 * drive. The drive then goes to the membrane, while the spikes written into the delay line
 * {@link #DELAY} earlier are read back, multiplied by the connectome's signed synapse counts and
 * by the potential of one synapse ({@link #SYNAPSE_WEIGHT} in the model), and go to the
 * synaptic potential. The neurons take both and emit
 * this step's spikes, which are written into the delay line and flow out of the step. The neuron
 * model and the Poisson input are Java types, placed in the pipe by naming them; the composition
 * around them is PDSL.</p>
 *
 * <p>The delay line has {@code delay + 1} rows of {@code N} spikes. On step {@code k} it is
 * written at row {@code k mod rows} and read at row {@code (k + 1) mod rows}, the row written
 * {@code delay} steps earlier, so the read never sees the row the same step writes.</p>
 *
 * <p>The network may hold several independent replicas of the connectome, stepped together: every
 * neuron, every Poisson draw and every row of the delay line is repeated for each replica, while
 * the connectome is shared, so the synaptic input of all replicas is one sparse product over a
 * batch. Replicas are the trials of an experiment run at once; with one replica the network is a
 * single brain.</p>
 *
 * <p>The network is a {@link Temporal} stepped by a {@link SimulationRunner}, which owns the loop;
 * {@link #getRunner()} returns the runner over this network's step counter. Its {@link Setup}
 * returns every neuron to rest and empties the delay line, so each run of the runner after a
 * reset is a fresh trial for every replica. {@link #meanRates(int, long, int)} runs a number of
 * trials, as many at once as there are replicas, each with its own Poisson draws, and returns the
 * mean firing rate of every neuron.</p>
 *
 * <p>Times are in milliseconds and potentials in millivolts; rates are in hertz.</p>
 */
public class ConnectomeNetwork implements Temporal, Setup, Destroyable, CollectionFeatures {

	/** The classpath resource holding the network step. */
	public static final String PDSL_RESOURCE = "/pdsl/connectome.pdsl";

	/** The layer of {@link #PDSL_RESOURCE} that is one network step. */
	public static final String STEP_LAYER = "connectome_step";

	/** The duration of one step. */
	public static final double DT = 0.1;

	/** The resting potential. */
	public static final double REST_POTENTIAL = -52.0;

	/** The potential a spike resets the membrane to. */
	public static final double RESET_POTENTIAL = -52.0;

	/** The potential the membrane must exceed to spike. */
	public static final double THRESHOLD_POTENTIAL = -45.0;

	/** The membrane time constant. */
	public static final double MEMBRANE_TIME = 20.0;

	/** The synaptic time constant. */
	public static final double SYNAPTIC_TIME = 5.0;

	/** The refractory period of a neuron that is not driven by a Poisson input. */
	public static final double REFRACTORY_PERIOD = 2.2;

	/** The synaptic delay. */
	public static final double DELAY = 1.8;

	/** The synaptic potential contributed by one synapse. */
	public static final double SYNAPSE_WEIGHT = 0.275;

	/** The membrane kick of one Poisson spike: 250 synapses' worth. */
	public static final double POISSON_KICK = 250 * SYNAPSE_WEIGHT;

	/**
	 * The largest delay line, in elements, this network accepts. {@code cache_read} and
	 * {@code cache_write} compute the element they address as {@code row * rowSize + i} in the
	 * backend's floating point, which on an FP32 backend is exact only below 2^24; past it, spikes
	 * are silently read from and written to neighbouring elements. This bounds the number of
	 * replicas: six replicas of the 127,400 neurons of FlyWire 630 fit, ten do not.
	 */
	public static final long DELAY_LINE_LIMIT = 1L << 24;

	/** The connectome, which belongs to the caller. */
	private final NeuronConnectome connectome;

	/** The indices of the neurons driven by the Poisson input. */
	private final int[] stimulated;

	/** The number of replicas of the connectome stepped together. */
	private final int replicas;

	/** The step counter, advanced by the runner. */
	private final PackedCollection step;

	/** 1 for each neuron of each replica driven by the Poisson input, written by the setup. */
	private final PackedCollection targets;

	/** The spike delay line, {@code [delay + 1, replicas * N]}. */
	private final PackedCollection ring;

	/** The spike counts of every neuron summed over the trials of {@link #meanRates(int, long, int)}. */
	private final PackedCollection totals;

	/** The neurons. */
	private final ExponentialSynapseNeurons neurons;

	/** The Poisson input. */
	private final PoissonInput poisson;

	/** The external drive, one membrane kick per neuron per step. */
	private final Producer<PackedCollection> drive;

	/** The silent drive this network allocated, or {@code null} for a caller's drive. */
	private final PackedCollection silence;

	/** The network step built from {@link #PDSL_RESOURCE}. */
	private final Block block;

	/** The runner over this network, created on first use. */
	private SimulationRunner runner;

	/**
	 * Creates a network of one replica with no external drive.
	 *
	 * @param connectome  the connectome; it belongs to the caller
	 * @param poissonRate the rate of the Poisson input to each stimulated neuron, in hertz
	 * @param seed        the Poisson seed of the first trial
	 * @param stimulated  the indices of the neurons driven by the Poisson input, possibly none
	 */
	public ConnectomeNetwork(NeuronConnectome connectome, double poissonRate, int seed, int... stimulated) {
		this(connectome, 1, poissonRate, seed, stimulated);
	}

	/**
	 * Creates a network with no external drive.
	 *
	 * @param connectome  the connectome; it belongs to the caller
	 * @param replicas    the number of independent replicas stepped together
	 * @param poissonRate the rate of the Poisson input to each stimulated neuron, in hertz
	 * @param seed        the Poisson seed of the first trial
	 * @param stimulated  the indices of the neurons driven by the Poisson input, possibly none
	 */
	public ConnectomeNetwork(NeuronConnectome connectome, int replicas, double poissonRate, int seed,
							 int... stimulated) {
		this(connectome, null, SYNAPSE_WEIGHT, replicas, poissonRate, seed, stimulated);
	}

	/**
	 * Creates a network.
	 *
	 * @param connectome  the connectome; it belongs to the caller
	 * @param drive       the external drive, {@code [rows, N]} membrane kicks: row
	 *                    {@code k mod rows} is added on step {@code k}, so a schedule has one row
	 *                    per step and a drive written from outside the network has one row; or
	 *                    {@code null} for none. Every replica receives the same drive. It belongs
	 *                    to the caller
	 * @param synapseWeight the synaptic potential contributed by one synapse, {@link #SYNAPSE_WEIGHT}
	 *                    in the model
	 * @param replicas    the number of independent replicas stepped together
	 * @param poissonRate the rate of the Poisson input to each stimulated neuron, in hertz
	 * @param seed        the Poisson seed of the first trial
	 * @param stimulated  the indices of the neurons driven by the Poisson input, possibly none
	 * @throws IllegalArgumentException if there is no replica, or the delay line of the replicas
	 *                                  would exceed {@link #DELAY_LINE_LIMIT}
	 */
	public ConnectomeNetwork(NeuronConnectome connectome, PackedCollection drive,
							 double synapseWeight, int replicas, double poissonRate, int seed,
							 int... stimulated) {
		if (replicas <= 0) {
			throw new IllegalArgumentException("A network needs at least one replica, got " + replicas);
		}

		int n = connectome.getNeuronCount();
		int count = replicas * n;
		int delaySteps = (int) ((DELAY + 1e-3 * DT) / DT);
		if ((long) (delaySteps + 1) * count > DELAY_LINE_LIMIT) {
			throw new IllegalArgumentException(replicas + " replicas of " + n + " neurons need a delay line of "
					+ (long) (delaySteps + 1) * count + " elements, more than " + DELAY_LINE_LIMIT
					+ " that can be addressed exactly");
		}

		this.connectome = connectome;
		this.stimulated = stimulated.clone();
		this.replicas = replicas;
		this.step = new PackedCollection(1);
		this.targets = new PackedCollection(shape(count));
		this.ring = new PackedCollection(shape(delaySteps + 1, count));
		this.totals = new PackedCollection(shape(n));
		this.silence = drive == null ? new PackedCollection(shape(1, n)) : null;
		this.drive = scheduled(drive == null ? silence : drive);

		this.neurons = new ExponentialSynapseNeurons(count, DT, REST_POTENTIAL, RESET_POTENTIAL,
				THRESHOLD_POTENTIAL, MEMBRANE_TIME, SYNAPTIC_TIME, REFRACTORY_PERIOD);
		this.neurons.exemptFromRefractoryPeriod(targets);

		try {
			this.poisson = new PoissonInput(poissonRate * 1e-3, DT, POISSON_KICK, targets, step, seed);
		} catch (RuntimeException e) {
			// release the state already allocated so an invalid Poisson argument does not leak it
			step.destroy();
			targets.destroy();
			ring.destroy();
			totals.destroy();
			neurons.destroy();
			if (silence != null) silence.destroy();
			throw e;
		}

		CollectionProducer rows = c((double) (delaySteps + 1));
		Map<String, Object> args = new HashMap<>();
		args.put("weights", connectome.getWeights());
		args.put("synapse_weight", synapseWeight);
		args.put("ring", ring);
		args.put("replica_shape", shape(replicas, n));
		args.put("read_row", mod(cp(step).add(c(1.0)), rows));
		args.put("write_row", mod(cp(step), rows));
		args.put("poisson", poisson);
		args.put("neurons", neurons);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource(PDSL_RESOURCE);
		this.block = loader.buildLayer(program, STEP_LAYER, shape(count), args);
		this.block.getForward().setReceptor(spikes -> new OperationList("connectome-step-output"));
	}

	/**
	 * Returns the row of a drive schedule that applies to the current step, row
	 * {@code step mod rows}, for every replica. The schedule is read at logical positions, so a
	 * schedule stored sparsely (a collection carrying a compressed-row ordering, one row per step)
	 * is read through its ordering like a dense one.
	 *
	 * @param schedule the {@code [rows, N]} drive schedule
	 * @return a producer of the {@code [replicas * N]} drive of the current step
	 * @throws IllegalArgumentException if the schedule does not have one column per neuron
	 */
	protected CollectionProducer scheduled(PackedCollection schedule) {
		int n = connectome.getNeuronCount();
		TraversalPolicy shape = schedule.getShape();
		if (shape.getDimensions() != 2 || shape.length(1) != n) {
			throw new IllegalArgumentException("Drive " + shape + " does not have one column for each of "
					+ n + " neurons");
		}

		CollectionProducer row = mod(cp(step), c((double) shape.length(0)));
		return c(shape(replicas * n), cp(schedule),
				integers(0, replicas * n).mod(c((double) n)).add(row.multiply(c((double) n))));
	}

	/**
	 * Builds one network step: the PDSL step applied to this step's external drive.
	 *
	 * @return the operations of one step
	 */
	@Override
	public Supplier<Runnable> tick() {
		return block.forward(drive);
	}

	/**
	 * Returns the network to its initial condition: the neurons at rest with no spikes counted,
	 * an empty delay line, and the Poisson targets of every replica written.
	 *
	 * @return the operations of the setup
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("connectome-network-setup");
		setup.add(stimulated.length == 0 ? a(cp(targets).each(), c(0.0))
				: a(cp(targets), connectome.mask(stimulated).traverse(0).repeat(replicas).reshape(shape(targets))));
		setup.add(a(cp(ring.reshape(shape(ring.getShape().getTotalSize()))).each(), c(0.0)));
		setup.add(block.setup());
		return setup;
	}

	/**
	 * Runs a number of trials and returns the firing rate of every neuron averaged over them.
	 * The trials run as many at once as there are replicas: each pass is a fresh network whose
	 * Poisson input has the seed {@code seed + pass}, and every replica of it draws independently.
	 *
	 * @param trials the number of trials, a multiple of the number of replicas
	 * @param steps  the number of steps of each trial
	 * @param seed   the Poisson seed of the first pass
	 * @return producer of the mean firing rate of every neuron in hertz, {@code [N]}, over the
	 *         spike counts of these trials
	 * @throws IllegalArgumentException if the number of trials or steps is not positive, or the
	 *                                  trials are not a multiple of the replicas
	 */
	public CollectionProducer meanRates(int trials, long steps, int seed) {
		if (trials <= 0 || steps <= 0 || trials % replicas != 0) {
			throw new IllegalArgumentException("Trials (" + trials + ") must be a positive multiple of "
					+ replicas + " replicas and steps (" + steps + ") positive");
		}

		int passes = trials / replicas;
		if (seed < 0 || (long) seed + passes > CounterRandom.SEED_LIMIT) {
			// validate the whole seed range up front, so a later pass cannot throw after an earlier one ran
			throw new IllegalArgumentException("Seeds " + seed + " through " + (seed + passes - 1)
					+ " of " + passes + " passes are not all within [0, " + CounterRandom.SEED_LIMIT + ")");
		}

		int n = connectome.getNeuronCount();
		SimulationRunner simulation = getRunner();
		a(cp(totals).each(), c(0.0)).get().run();
		CollectionProducer counts = cp(neurons.getSpikeCounts().reshape(shape(replicas, n)))
				.permute(1, 0).sum(1).reshape(shape(n));
		Runnable accumulate = a(cp(totals), cp(totals).add(counts)).get();

		for (int pass = 0; pass < passes; pass++) {
			poisson.setSeed(seed + pass);
			simulation.reset();
			simulation.run(steps);
			accumulate.run();
		}

		return cp(totals).multiply(c(1000.0 / (trials * steps * DT)));
	}

	/**
	 * Runs a number of trials from seed zero; see {@link #meanRates(int, long, int)}.
	 *
	 * @param trials the number of trials
	 * @param steps  the number of steps of each trial
	 * @return producer of the mean firing rate of every neuron in hertz, {@code [N]}
	 */
	public CollectionProducer meanRates(int trials, long steps) {
		return meanRates(trials, steps, 0);
	}

	/**
	 * Returns the runner that steps this network over its step counter, creating it on first use.
	 *
	 * @return the runner
	 */
	public SimulationRunner getRunner() {
		if (runner == null) {
			runner = new SimulationRunner(this, DT, step);
		}

		return runner;
	}

	/**
	 * Returns the number of replicas stepped together.
	 *
	 * @return the number of replicas
	 */
	public int getReplicas() { return replicas; }

	/**
	 * Returns the connectome.
	 *
	 * @return the connectome
	 */
	public NeuronConnectome getConnectome() { return connectome; }

	/**
	 * Returns the neurons.
	 *
	 * @return the neurons
	 */
	public ExponentialSynapseNeurons getNeurons() { return neurons; }

	/**
	 * Returns the Poisson input.
	 *
	 * @return the Poisson input
	 */
	public PoissonInput getPoisson() { return poisson; }

	/**
	 * Returns the step counter, which a drive may read.
	 *
	 * @return the step counter, which a drive may read
	 */
	public PackedCollection getStep() { return step; }

	/**
	 * Returns the spike delay line, {@code [delay + 1, replicas * N]}.
	 *
	 * @return the spike delay line
	 */
	public PackedCollection getRing() { return ring; }

	/** Releases the runner, the neurons, the Poisson input and the network's own state. */
	@Override
	public void destroy() {
		if (runner != null) runner.destroy();
		poisson.destroy();
		neurons.destroy();
		ring.destroy();
		totals.destroy();
		targets.destroy();
		step.destroy();
		if (silence != null) silence.destroy();
	}
}
