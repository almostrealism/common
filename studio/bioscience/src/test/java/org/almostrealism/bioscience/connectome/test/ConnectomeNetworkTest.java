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
import org.almostrealism.bioscience.connectome.ConnectomeNetwork;
import org.almostrealism.bioscience.connectome.NeuronConnectome;
import org.almostrealism.collect.CompressedRowTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CounterRandom;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.time.SimulationRunner;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * {@link ConnectomeNetwork} against Brian2 runs of the same equations, on two committed
 * connectomes: the synthetic one, and the FlyWire 630 sugar circuit, the 451 neurons that fire
 * in the Brian2 sugar experiment with every edge joining them, at the model's own synapse weight.
 * Each reference, written by {@code brian2_reference.py synthetic} or {@code circuit}, is a
 * seeded schedule of membrane kicks and every spike that follows, as {@code (step, neuron)}
 * events. The schedule makes a run deterministic, so the network must reproduce the spikes, up
 * to a one-step shift of a rare spike that FP32 puts on the other side of the threshold (see
 * {@link #assertMatchesBrian2}). The sugar circuit is the full experiment scaled down: the same connectome, signs,
 * synapse weight, delays and input neurons, over half a second of simulated time with MN9
 * firing near its full-brain rate. Needs no dataset, so it runs everywhere.
 */
public class ConnectomeNetworkTest extends NeuronConnectomeChecks {
	/** The directory of the synthetic connectome's Brian2 reference, under the test resources. */
	public static final String BRIAN2_RESOURCE = "/connectome/synthetic-brian2/";

	/** The directory of the sugar circuit's Brian2 reference, under the test resources. */
	public static final String SUGAR_CIRCUIT_BRIAN2_RESOURCE = "/connectome/sugar-circuit-brian2/";

	/** The FlyWire root id of MN9, the proboscis motor neuron the sugar circuit drives. */
	public static final long MN9 = 720575940660219265L;

	/**
	 * Under the reference's kick schedule and synapse weight, the network emits exactly the
	 * spikes Brian2 emits: the same neurons on the same steps, including every spike carried
	 * through the delayed, signed synapses.
	 */
	@Test(timeout = 300000)
	public void matchesBrian2Spikes() throws IOException {
		assertMatchesBrian2(synthetic(), BRIAN2_RESOURCE, 1);
	}

	/**
	 * Three replicas under the same schedule each emit exactly the spikes Brian2 emits, so the
	 * batched sparse product keeps the replicas apart, and the mean rates of one pass of three
	 * trials are the reference's spike counts over the run.
	 */
	@Test(timeout = 300000)
	public void replicasEachMatchBrian2Spikes() throws IOException {
		assertMatchesBrian2(synthetic(), BRIAN2_RESOURCE, 3);
	}

	/**
	 * On the FlyWire sugar circuit, under kicks to the 21 sugar GRNs at 200 Hz and the model's
	 * synapse weight of 0.275 mV, the network emits the spikes Brian2 emits over 5000 steps, MN9's
	 * among them, up to the FP32 timing allowance of {@link #assertMatchesBrian2}.
	 */
	@Test(timeout = 600000)
	public void sugarCircuitMatchesBrian2Spikes() throws IOException {
		int[] counts = assertMatchesBrian2(sugarCircuit(), SUGAR_CIRCUIT_BRIAN2_RESOURCE, 1);
		try (NeuronConnectome connectome = NeuronConnectome.load(sugarCircuit())) {
			int mn9 = connectome.indexOf(MN9);
			log("mn9Spikes=" + counts[mn9]);
			Assert.assertTrue("MN9 is silent in the reference", counts[mn9] > 20);
		}
	}

	/**
	 * Two replicas of the sugar circuit under the same schedule each emit the spikes Brian2 emits,
	 * so the batched sparse product keeps replicas of a real connectome apart.
	 */
	@Test(timeout = 600000)
	public void sugarCircuitReplicasEachMatchBrian2Spikes() throws IOException {
		assertMatchesBrian2(sugarCircuit(), SUGAR_CIRCUIT_BRIAN2_RESOURCE, 2);
	}

	/**
	 * Groups {@code (step, neuron)} events by neuron.
	 *
	 * @param events  the events, step and neuron interleaved, in step order
	 * @param neurons the number of neurons
	 * @return the steps of each neuron's events, in order
	 */
	protected static int[][] byNeuron(int[] events, int neurons) {
		return IntStream.range(0, neurons)
				.mapToObj(i -> IntStream.range(0, events.length / 2)
						.filter(e -> events[2 * e + 1] == i).map(e -> events[2 * e]).toArray())
				.toArray(int[][]::new);
	}

	/**
	 * Runs the network on a connectome with a number of replicas under a reference's kick
	 * schedule and synapse weight, and asserts that every replica emits the reference's spikes:
	 * each neuron the same number, each within one step of the reference's, and at most one in a
	 * thousand displaced at all. Brian2 integrates in FP64; an FP32 backend can put a membrane that
	 * the reference leaves a hair above threshold just below it, and the neuron then crosses one
	 * step later (on the sugar circuit, a NumPy FP64 run of the same step reproduces all 7917
	 * reference spikes and an FP32 run shifts exactly one neuron's spike at step 925 by one step).
	 * A run with fewer than a thousand spikes must therefore match exactly. The mean rates of one
	 * pass must be the reference's spike counts over the run.
	 *
	 * @param directory the connectome directory
	 * @param resource  the directory of the Brian2 reference, under the test resources
	 * @param replicas  the number of replicas
	 * @return the reference's spike count of each neuron
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	protected int[] assertMatchesBrian2(File directory, String resource, int replicas) throws IOException {
		File reference = resourceDirectory(resource + "manifest.json");
		JsonObject manifest = json(reference, "manifest.json").getAsJsonObject();
		int steps = manifest.get("steps").getAsInt();
		double synapseWeight = manifest.get("synapse_mv").getAsDouble();
		int[] driven = StreamSupport.stream(manifest.getAsJsonArray("driven").spliterator(), false)
				.mapToInt(e -> e.getAsInt()).toArray();
		int[] counts = StreamSupport.stream(manifest.getAsJsonArray("spikes_per_neuron").spliterator(), false)
				.mapToInt(e -> e.getAsInt()).toArray();

		try (NeuronConnectome connectome = NeuronConnectome.load(directory);
			 StateDictionary brian = new StateDictionary(reference.getPath())) {
			int n = connectome.getNeuronCount();
			CompressedRowTraversalOrdering schedule = CompressedRowTraversalOrdering.of(n,
					brian.require("kick_offsets"), brian.require("kick_neurons"));
			PackedCollection drive = new PackedCollection(shape(steps, n), 0, brian.require("kick_values"), 0, schedule);
			ConnectomeNetwork network = new ConnectomeNetwork(connectome, drive,
					synapseWeight, replicas, 0.0, 0, driven);
			SimulationRunner runner = network.getRunner();
			SimulationRunner.Recording spikes = runner.record(cp(network.getNeurons().getSpikes()), 1, steps);
			runner.run(steps);

			double[] actual = spikes.getBuffer().toArray();
			int[][] expected = byNeuron(brian.require("spikes").toIntArray(), n);
			int total = manifest.get("spikes").getAsInt();
			for (int r = 0; r < replicas; r++) {
				int replica = r;
				int[] events = IntStream.range(0, steps * n)
						.filter(i -> actual[((i / n) * replicas + replica) * n + i % n] != 0.0)
						.flatMap(i -> IntStream.of(i / n, i % n)).toArray();
				int[][] emitted = byNeuron(events, n);

				int shifted = 0;
				for (int i = 0; i < n; i++) {
					Assert.assertEquals("replica " + r + " spike count of neuron " + i,
							expected[i].length, emitted[i].length);
					for (int k = 0; k < expected[i].length; k++) {
						int offset = emitted[i][k] - expected[i][k];
						Assert.assertTrue("replica " + r + " neuron " + i + " spiked at step " + emitted[i][k]
								+ ", Brian2 at step " + expected[i][k], Math.abs(offset) <= 1);
						if (offset != 0) shifted++;
					}
				}

				log("replica=" + r + " brianSpikes=" + total + " shiftedSpikes=" + shifted);
				Assert.assertTrue(shifted + " spikes shifted by a step", shifted <= total / 1000);
			}

			double[] rates = network.meanRates(replicas, steps).evaluate().toArray();
			for (int i = 0; i < n; i++) {
				Assert.assertEquals("rate of neuron " + i, counts[i] * 1000.0 / (steps * ConnectomeNetwork.DT),
						rates[i], 1e-3);
			}

			network.destroy();
			schedule.getRowOffsets().destroy();
			schedule.getColumns().destroy();
			return counts;
		}
	}

	/**
	 * Neurons driven by a 150 Hz Poisson input fire close to that rate (a kick arriving on the
	 * step of a spike is cleared by the reset, so slightly below it), and with the model's synapse
	 * weight the synthetic connectome is too weak to carry their activity to any other neuron.
	 */
	@Test(timeout = 300000)
	public void poissonInputDrivesItsTargets() throws IOException {
		int[] stimulated = {0, 1, 2};
		try (NeuronConnectome connectome = NeuronConnectome.load(synthetic())) {
			ConnectomeNetwork network = new ConnectomeNetwork(connectome, 150.0, 11, stimulated);
			double[] rates = network.meanRates(5, 2000).evaluate().toArray();
			for (int i = 0; i < rates.length; i++) {
				if (i < stimulated.length) {
					Assert.assertEquals("neuron " + i, 147.0, rates[i], 30.0);
				} else {
					Assert.assertEquals("neuron " + i, 0.0, rates[i], 0.0);
				}
			}

			network.destroy();
		}
	}

	/** Without any input, every neuron stays at rest and the network is silent. */
	@Test(timeout = 300000)
	public void silentWithoutInput() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(synthetic())) {
			ConnectomeNetwork network = new ConnectomeNetwork(connectome, 150.0, 0);
			double[] rates = network.meanRates(1, 2000).evaluate().toArray();
			for (int i = 0; i < rates.length; i++) {
				Assert.assertEquals("neuron " + i, 0.0, rates[i], 0.0);
			}

			network.destroy();
		}
	}

	/**
	 * The constructor rejects a Poisson seed outside its range and a negative rate as invalid
	 * arguments, so a caller cannot build a network that would draw from an invalid stream. The
	 * rejection releases the network state the constructor had already allocated rather than
	 * leaking it (see {@link ConnectomeNetwork}'s constructor cleanup).
	 */
	@Test(timeout = 300000)
	public void constructorRejectsInvalidPoissonArguments() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(synthetic())) {
			assertConstructorRejected(connectome, 150.0, CounterRandom.SEED_LIMIT);
			assertConstructorRejected(connectome, 150.0, -1);
			assertConstructorRejected(connectome, -1.0, 0);
		}
	}

	/**
	 * Asserts that {@link ConnectomeNetwork}'s constructor rejects a Poisson rate and seed with an
	 * {@link IllegalArgumentException}.
	 *
	 * @param connectome the connectome
	 * @param rate       the Poisson rate in hertz
	 * @param seed       the Poisson seed
	 */
	private void assertConstructorRejected(NeuronConnectome connectome, double rate, int seed) {
		try {
			new ConnectomeNetwork(connectome, rate, seed).destroy();
			Assert.fail("Accepted rate " + rate + " and seed " + seed);
		} catch (IllegalArgumentException expected) {
			log("rejected=" + expected.getMessage());
		}
	}

	/**
	 * {@link ConnectomeNetwork#meanRates(int, long, int)} rejects a seed range whose later passes
	 * would run past {@link CounterRandom#SEED_LIMIT}, and does so before any state is cleared or
	 * any trial run, rather than throwing partway through after earlier passes have already mutated
	 * the network.
	 */
	@Test(timeout = 300000)
	public void meanRatesRejectsSeedRangeOverflow() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(synthetic())) {
			ConnectomeNetwork network = new ConnectomeNetwork(connectome, 150.0, 0, 0);
			try {
				network.meanRates(2, 10, CounterRandom.SEED_LIMIT - 1);
				Assert.fail("Accepted a two-pass run whose second seed is past the limit");
			} catch (IllegalArgumentException expected) {
				log("rejected=" + expected.getMessage());
			} finally {
				network.destroy();
			}
		}
	}
}
