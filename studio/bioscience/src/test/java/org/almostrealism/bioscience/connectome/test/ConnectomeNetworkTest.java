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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.time.SimulationRunner;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.stream.StreamSupport;

/**
 * {@link ConnectomeNetwork} on the committed synthetic connectome. The reference is a Brian2
 * run of the same equations, written by {@code brian2_reference.py synthetic} into
 * {@code src/test/resources/connectome/synthetic-brian2}: a seeded schedule of membrane kicks to
 * five neurons, and every spike of the 2000 steps that follow. The schedule makes the run
 * deterministic, so the network must reproduce the spikes exactly. Needs no dataset, so it runs
 * everywhere.
 */
public class ConnectomeNetworkTest extends NeuronConnectomeChecks {
	/** The directory of the Brian2 reference, under the test resources. */
	public static final String BRIAN2_RESOURCE = "/connectome/synthetic-brian2/";

	/**
	 * Under the reference's kick schedule and synapse weight, the network emits exactly the
	 * spikes Brian2 emits: the same neurons on the same steps, including every spike carried
	 * through the delayed, signed synapses.
	 */
	@Test(timeout = 300000)
	public void matchesBrian2Spikes() throws IOException {
		assertMatchesBrian2(1);
	}

	/**
	 * Three replicas under the same schedule each emit exactly the spikes Brian2 emits, so the
	 * batched sparse product keeps the replicas apart, and the mean rates of one pass of three
	 * trials are the reference's spike counts over the run.
	 */
	@Test(timeout = 300000)
	public void replicasEachMatchBrian2Spikes() throws IOException {
		assertMatchesBrian2(3);
	}

	/**
	 * Runs the network with a number of replicas under the reference's kick schedule and synapse
	 * weight, and asserts that every replica emits exactly the reference's spikes and that the
	 * mean rates of one pass are the reference's spike counts over the run.
	 *
	 * @param replicas the number of replicas
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	protected void assertMatchesBrian2(int replicas) throws IOException {
		File reference = resourceDirectory(BRIAN2_RESOURCE + "manifest.json");
		JsonObject manifest = json(reference, "manifest.json").getAsJsonObject();
		int steps = manifest.get("steps").getAsInt();
		double synapseWeight = manifest.get("synapse_mv").getAsDouble();
		int[] driven = StreamSupport.stream(manifest.getAsJsonArray("driven").spliterator(), false)
				.mapToInt(e -> e.getAsInt()).toArray();
		int[] counts = StreamSupport.stream(manifest.getAsJsonArray("spikes_per_neuron").spliterator(), false)
				.mapToInt(e -> e.getAsInt()).toArray();

		try (NeuronConnectome connectome = NeuronConnectome.load(synthetic());
			 StateDictionary brian = new StateDictionary(reference.getPath())) {
			int n = connectome.getNeuronCount();
			ConnectomeNetwork network = new ConnectomeNetwork(connectome, brian.get("drive"),
					synapseWeight, replicas, 0.0, 0, driven);
			SimulationRunner runner = network.getRunner();
			SimulationRunner.Recording spikes = runner.record(cp(network.getNeurons().getSpikes()), 1, steps);
			runner.run(steps);

			double[] actual = spikes.getBuffer().toArray();
			double[] expected = brian.get("spikes").toArray();
			int mismatches = 0;
			String first = null;
			for (int i = 0; i < expected.length; i++) {
				int step = i / n;
				for (int r = 0; r < replicas; r++) {
					double value = actual[(step * replicas + r) * n + i % n];
					if (value != expected[i]) {
						mismatches++;
						if (first == null) {
							first = "replica " + r + " step " + step + " neuron " + (i % n)
									+ ": expected " + expected[i] + ", got " + value;
						}
					}
				}
			}

			log("replicas=" + replicas + " brianSpikes=" + manifest.get("spikes").getAsInt()
					+ " mismatches=" + mismatches);
			Assert.assertEquals("Spikes differing from Brian2, first at " + first, 0, mismatches);

			double[] rates = network.meanRates(replicas, steps).evaluate().toArray();
			for (int i = 0; i < n; i++) {
				Assert.assertEquals("rate of neuron " + i, counts[i] * 1000.0 / (steps * ConnectomeNetwork.DT),
						rates[i], 1e-3);
			}

			network.destroy();
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
}
