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

package org.almostrealism.bioscience.connectome.test;

import com.google.gson.JsonObject;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.bioscience.connectome.ConnectomeNetwork;
import org.almostrealism.bioscience.connectome.NeuronConnectome;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.time.SimulationRunner;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * Pins the worked example of the neuron-level connectome simulation internals page
 * ({@code docs/internals/neuron-level-connectome-simulation.md}): the committed synthetic
 * connectome (40 neurons, 111 signed edges) loaded by {@link NeuronConnectome}, driven by the
 * seeded membrane-kick schedule of its committed Brian2 reference, composed one step at a time in
 * {@code /pdsl/connectome.pdsl} by {@link ConnectomeNetwork} and stepped by a
 * {@link SimulationRunner}, with the spike vector recorded on the device and read once the run is
 * over.
 *
 * <p>Every complete code excerpt the page shows is exercised here, so the page cannot silently
 * rot. The tests assert the page's claims: under the reference's kick schedule the network emits
 * exactly the spikes Brian2 emits (the synthetic reference has fewer than a thousand spikes, so it
 * must match spike for spike on any backend, with no timing allowance); a driven neuron fires and
 * its activity reaches downstream neurons through the signed sparse product; {@code meanRates}
 * returns the reference's firing rates; a run repeated after {@link SimulationRunner#reset()}
 * records the identical raster; and several replicas stepped together as one batched sparse product
 * each emit the reference's spikes.</p>
 *
 * <p>The synthetic connectome and its Brian2 reference ship as test resources, so this runs on any
 * backend with no curated download and no particular node. It asserts only robust, backend-exact
 * properties of the committed fixture; it reduces no dimension, loosens no tolerance, and gates no
 * skip on an environment probe. The spike-for-spike correctness of the network, including the
 * one-step FP32 timing allowance the larger datasets need, is {@link ConnectomeNetworkTest}; this
 * is the documentation drift-guard.</p>
 */
public class NeuronLevelConnectomeSimulationExamplesTest extends NeuronConnectomeChecks {
	/** The directory of the synthetic connectome's Brian2 reference, under the test resources. */
	public static final String BRIAN2_RESOURCE = "/connectome/synthetic-brian2/";

	/**
	 * The worked example of the page: the synthetic connectome, the drive built from its committed
	 * Brian2 reference, the network composed around the connectome, the runner that owns the loop,
	 * and the recording of the spike vector. {@link #destroy()} releases them in the order the page
	 * prescribes: the network (and with it the runner, the compiled step and the recording) before
	 * the connectome and the collections it reads.
	 *
	 * <p>The connectome is loaded through {@link NeuronConnectome#load}; the drive is a
	 * {@code [steps, N]} collection of membrane kicks whose rows are stored sparsely (one
	 * compressed row per step) and read through a {@link CompressedRowTraversalOrdering} like a
	 * dense drive; the network is built with the reference's synapse weight, the driven neurons
	 * (whose refractory period Brian2 removes under a Poisson drive) and no Poisson input of its
	 * own, since the drive carries every kick.</p>
	 */
	private class Simulation implements Destroyable {
		/** The synthetic connectome, 40 neurons and 111 signed edges. */
		final NeuronConnectome connectome;

		/** The reference's kick schedule, one compressed row of membrane kicks per step. */
		final CompressedRowTraversalOrdering schedule;

		/** The drive, {@code [steps, N]} membrane kicks, read through {@link #schedule}. */
		final PackedCollection drive;

		/** The spiking network over the connectome. */
		final ConnectomeNetwork network;

		/** The simulation loop owner over the network's step counter. */
		final SimulationRunner runner;

		/** The recording of the spike vector after every step. */
		final SimulationRunner.Recording raster;

		/** The number of steps of the reference run. */
		final int steps;

		/** The number of neurons. */
		final int n;

		/** The number of replicas stepped together. */
		final int replicas;

		/** The neurons the reference drives, the only ones its kick schedule may kick. */
		final int[] driven;

		/** The reference spike count of every neuron, from the reference manifest. */
		final int[] counts;

		/** The reference's {@code (step, neuron)} spike events, interleaved in step order. */
		final int[] events;

		/** The reference tensors, whose kick values and offsets the drive and schedule share. */
		final StateDictionary reference;

		/**
		 * Builds the worked example on the synthetic connectome with a number of replicas.
		 *
		 * @param replicas the number of independent replicas stepped together
		 * @throws IOException if the connectome or the reference cannot be read
		 */
		Simulation(int replicas) throws IOException {
			this.replicas = replicas;

			connectome = NeuronConnectome.load(synthetic());
			n = connectome.getNeuronCount();

			JsonObject manifest = json(resourceDirectory(BRIAN2_RESOURCE + "manifest.json"),
					"manifest.json").getAsJsonObject();
			steps = manifest.get("steps").getAsInt();
			double synapseWeight = manifest.get("synapse_mv").getAsDouble();
			driven = StreamSupport.stream(manifest.getAsJsonArray("driven").spliterator(), false)
					.mapToInt(e -> e.getAsInt()).toArray();
			counts = StreamSupport.stream(manifest.getAsJsonArray("spikes_per_neuron").spliterator(), false)
					.mapToInt(e -> e.getAsInt()).toArray();

			reference = new StateDictionary(
					resourceDirectory(BRIAN2_RESOURCE + "manifest.json").getPath());
			events = reference.require("spikes").toIntArray();

			schedule = CompressedRowTraversalOrdering.of(n, reference.require("kick_offsets"),
					reference.require("kick_neurons"));
			drive = new PackedCollection(shape(steps, n), 0, reference.require("kick_values"), 0, schedule);

			network = new ConnectomeNetwork(connectome, drive, synapseWeight, replicas, 0.0, 0, driven);

			runner = network.getRunner();
			raster = runner.record(cp(network.getNeurons().getSpikes()), 1, steps);
		}

		/**
		 * Returns the recorded spikes of one replica as a {@code [steps, N]} raster: 1 where that
		 * replica's neuron spiked on that step and 0 elsewhere. The recording interleaves the
		 * replicas, so replica {@code r}'s neuron {@code j} on step {@code t} is at
		 * {@code (t * replicas + r) * N + j}.
		 *
		 * @param replica the replica index
		 * @return the {@code steps * N} raster of that replica
		 */
		double[] replicaRaster(int replica) {
			double[] buffer = raster.getBuffer().toArray();
			return IntStream.range(0, steps * n)
					.mapToDouble(i -> buffer[((i / n) * replicas + replica) * n + i % n]).toArray();
		}

		/**
		 * Returns the reference raster the network must reproduce: a {@code [steps, N]} array with 1
		 * at every {@code (step, neuron)} event of the reference.
		 *
		 * @return the reference raster, {@code steps * N}
		 */
		double[] referenceRaster() {
			double[] expected = new double[steps * n];
			for (int e = 0; e < events.length / 2; e++) {
				expected[events[2 * e] * n + events[2 * e + 1]] = 1.0;
			}
			return expected;
		}

		/** Releases the network (and its runner), then the connectome and the collections it reads. */
		@Override
		public void destroy() {
			network.destroy();
			schedule.getRowOffsets().destroy();
			schedule.getColumns().destroy();
			connectome.destroy();
			reference.destroy();
		}
	}

	/**
	 * Under the reference's kick schedule the network emits exactly the spikes Brian2 emits on the
	 * synthetic connectome: the same neurons on the same steps. The synthetic reference has fewer
	 * than a thousand spikes, so the match is exact on any backend, with no timing allowance.
	 *
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	@Test(timeout = 300000)
	public void syntheticSpikesMatchBrian2() throws IOException {
		Simulation simulation = new Simulation(1);
		try {
			simulation.runner.run(simulation.steps);

			double[] expected = simulation.referenceRaster();
			Assert.assertArrayEquals("the network's raster differs from the Brian2 reference",
					expected, simulation.replicaRaster(0), 0.0);

			double total = IntStream.of(simulation.counts).sum();
			double emitted = 0.0;
			for (double spike : simulation.replicaRaster(0)) emitted += spike;
			log("brianSpikes=" + total + " emittedSpikes=" + emitted);
			Assert.assertEquals("spike total", total, emitted, 0.0);
		} finally {
			simulation.destroy();
		}
	}

	/**
	 * A driven neuron fires, and the signed sparse product carries its activity to at least one
	 * neuron the schedule never kicks, so the connectome is doing work rather than the drive alone
	 * being observed. The spikes are those the network records over the run, not the reference's.
	 *
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	@Test(timeout = 300000)
	public void drivenActivityReachesDownstreamNeurons() throws IOException {
		Simulation simulation = new Simulation(1);
		try {
			int[] driven = simulation.driven;
			for (int kicked : simulation.reference.require("kick_neurons").toIntArray()) {
				Assert.assertTrue("the schedule kicks undriven neuron " + kicked,
						IntStream.of(driven).anyMatch(d -> d == kicked));
			}

			simulation.runner.run(simulation.steps);
			double[] raster = simulation.replicaRaster(0);
			int n = simulation.n;
			double[] emitted = new double[n];
			for (int i = 0; i < raster.length; i++) emitted[i % n] += raster[i];

			Assert.assertTrue("the driven neuron " + driven[0] + " is silent",
					emitted[driven[0]] > 0);

			int downstream = IntStream.range(0, n)
					.filter(i -> IntStream.of(driven).noneMatch(d -> d == i))
					.filter(i -> emitted[i] > 0).findFirst().orElse(-1);
			log("firstDownstreamNeuron=" + downstream);
			Assert.assertTrue("no undriven neuron fires, so the connectome carries no activity",
					downstream >= 0);
			Assert.assertEquals("spikes of downstream neuron " + downstream,
					simulation.counts[downstream], emitted[downstream], 0.0);
		} finally {
			simulation.destroy();
		}
	}

	/**
	 * {@link ConnectomeNetwork#meanRates(int, long)} returns each neuron's firing rate over the run,
	 * which under the deterministic schedule is the reference's spike count scaled to hertz.
	 *
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	@Test(timeout = 300000)
	public void meanRatesAreTheReferenceRates() throws IOException {
		Simulation simulation = new Simulation(1);
		try {
			double[] rates = simulation.network.meanRates(1, simulation.steps).evaluate().toArray();
			for (int i = 0; i < simulation.n; i++) {
				Assert.assertEquals("rate of neuron " + i,
						simulation.counts[i] * 1000.0 / (simulation.steps * ConnectomeNetwork.DT),
						rates[i], 1e-3);
			}
		} finally {
			simulation.destroy();
		}
	}

	/**
	 * A run repeated after {@link SimulationRunner#reset()} records the identical raster: the reset
	 * returns the neurons to rest and empties the delay line, so the second run is the same trial.
	 *
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	@Test(timeout = 300000)
	public void resetRunReproducesTheRaster() throws IOException {
		Simulation simulation = new Simulation(1);
		try {
			simulation.runner.run(simulation.steps);
			double[] first = simulation.replicaRaster(0);

			simulation.runner.reset();
			simulation.runner.run(simulation.steps);
			Assert.assertArrayEquals("a run repeated after reset recorded a different raster",
					first, simulation.replicaRaster(0), 0.0);
		} finally {
			simulation.destroy();
		}
	}

	/**
	 * Three replicas stepped together as one batched sparse product each emit exactly the spikes
	 * Brian2 emits, so the batch keeps the replicas apart.
	 *
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	@Test(timeout = 300000)
	public void replicasEachMatchBrian2() throws IOException {
		Simulation simulation = new Simulation(3);
		try {
			simulation.runner.run(simulation.steps);

			double[] expected = simulation.referenceRaster();
			for (int r = 0; r < simulation.replicas; r++) {
				Assert.assertArrayEquals("replica " + r + " differs from the Brian2 reference",
						expected, simulation.replicaRaster(r), 0.0);
			}
		} finally {
			simulation.destroy();
		}
	}
}
