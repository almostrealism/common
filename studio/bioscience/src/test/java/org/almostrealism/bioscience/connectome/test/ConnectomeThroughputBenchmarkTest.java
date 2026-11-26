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

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.bioscience.connectome.ConnectomeNetwork;
import org.almostrealism.bioscience.connectome.NeuronConnectome;
import org.almostrealism.biology.neuron.ExponentialSynapseNeurons;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

/**
 * Measures the throughput of the neuron-level spiking simulation of the whole MaleCNS
 * connectome: one replica at the Shiu et al. (2024) constants, its sugar-sensing gustatory
 * receptor neurons driven by Poisson input, the configuration the MaleCNS experiments of
 * {@link ConnectomeNetworkDatasetTest} validate against Brian2.
 *
 * <p>The benchmark logs the time per step ({@code perStepMicros=}), the simulated seconds per
 * wall-clock second ({@code simulatedPerWallSecond=}), the kernel dispatches and command-buffer
 * commits per step on Metal (zero on other backends) and the memory footprint of the network's
 * tensors. The step is compiled with an {@link OperationProfileNode}, also assigned to the
 * hardware so that it records the generated source and run time of every kernel, and saved to
 * {@code results/connectomeStep.xml}, from which the per-operation time of the sparse product,
 * the neuron update and the delay line, and the generated source, can be read; the time a step
 * spends outside its operations is the per-step time less their summed time. Where the profile
 * cannot see device time (asynchronous submission), {@link #maleCnsSparseProductTime()} times
 * the sparse product on its own.</p>
 *
 * <p>Only the finiteness of the neuron state and the activity of the driven neurons are
 * asserted, never a time threshold. The connectome lives outside the repository (see
 * {@link NeuronConnectomeChecks#maleCns()}), so the benchmark is long-running and excluded from
 * the pipeline profile; when it is asked for, missing data fails it on every host. It is meant to
 * be run on each backend ({@code AR_HARDWARE_DRIVER}) with {@code AR_HARDWARE_MEMORY_SCALE=6}.</p>
 */
public class ConnectomeThroughputBenchmarkTest extends NeuronConnectomeChecks {
	/**
	 * The MaleCNS types taken as the sugar-sensing gustatory receptor neurons, the labellar
	 * bristle types that MaleCNS matches to the FlyWire type {@code LB3}.
	 */
	public static final List<String> SUGAR_GRNS = List.of("LB3a", "LB3b", "LB3c", "LB3d");

	/** The Poisson rate of the input to each sugar-sensing neuron, the model's default, in hertz. */
	public static final double RATE = 150.0;

	/** Steps run before timing, which include compiling the step. */
	public static final int WARMUP = 200;

	/** Steps timed: 200 ms of simulated time, well under the {@code 2^24} step-counter bound. */
	public static final int STEPS = 2000;

	/**
	 * Runs the profiled whole-brain step, logs its throughput, dispatch counts and memory
	 * footprint, and saves the profile to {@code results/connectomeStep.xml}.
	 *
	 * @throws IOException if the connectome cannot be read or the profile cannot be saved
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void maleCnsStepThroughput() throws IOException {
		OperationProfileNode profile = new OperationProfileNode("connectomeStep");
		Hardware.getLocalHardware().assignProfile(profile);

		try (NeuronConnectome connectome = NeuronConnectome.load(maleCns())) {
			int[] stimulated = connectome.indicesOfType(SUGAR_GRNS::contains);
			Assert.assertTrue("no neuron of the types " + SUGAR_GRNS, stimulated.length > 0);

			ConnectomeNetwork network = new ConnectomeNetwork(connectome, 1, RATE, 0, stimulated);
			SimulationRunner runner = new SimulationRunner(network, ConnectomeNetwork.DT, network.getStep(), profile);

			try {
				logFootprint(network);
				runner.run(WARMUP);

				long dispatches = MetalCommandRunner.totalDispatchCount.get();
				long commits = MetalCommandRunner.totalCommitCount.get();
				long start = System.nanoTime();
				runner.run(STEPS);
				double wallSeconds = (System.nanoTime() - start) / 1e9;
				double simulatedSeconds = STEPS * ConnectomeNetwork.DT / 1000;

				log("backend=" + Hardware.getLocalHardware().getDataContext().getName()
						+ " precision=" + Hardware.getLocalHardware().getPrecision()
						+ " neurons=" + connectome.getNeuronCount() + " edges=" + connectome.getEdgeCount()
						+ " stimulated=" + stimulated.length + " steps=" + STEPS);
				log("perStepMicros=" + wallSeconds * 1e6 / STEPS
						+ " simulatedPerWallSecond=" + simulatedSeconds / wallSeconds
						+ " dispatchesPerStep=" + (MetalCommandRunner.totalDispatchCount.get() - dispatches) / (double) STEPS
						+ " commitsPerStep=" + (MetalCommandRunner.totalCommitCount.get() - commits) / (double) STEPS);

				ExponentialSynapseNeurons neurons = network.getNeurons();
				Assert.assertTrue("non-finite membrane potential",
						DoubleStream.of(neurons.getMembrane().toArray()).allMatch(Double::isFinite));
				double spikes = DoubleStream.of(neurons.getSpikeCounts().toArray()).sum();
				log("totalSpikes=" + spikes);
				Assert.assertTrue("no neuron fired under the sugar input", spikes > 0);
			} finally {
				runner.destroy();
				network.destroy();
			}
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
			profile.save("results/connectomeStep.xml");
		}
	}

	/**
	 * Steps the sparse product of the MaleCNS connectome alone, the {@code dense(weights)} of the
	 * network step, through a {@link SimulationRunner} and logs its time per step
	 * ({@code sparseProductMicros=}). On a backend that submits asynchronously the operation
	 * profile of {@link #maleCnsStepThroughput()} records when each kernel was issued rather than
	 * how long the device spent in it; the difference between the two per-step times is what the
	 * rest of the network step (the neuron update, the Poisson input, the delay line and the
	 * dispatches) costs on that backend. The product visits every stored edge whatever the
	 * presynaptic vector holds, so every neuron is taken to have spiked.
	 *
	 * @throws IOException if the connectome cannot be read
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void maleCnsSparseProductTime() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(maleCns());
			 PackedCollection spikes = new PackedCollection(shape(connectome.getNeuronCount()));
			 PackedCollection input = new PackedCollection(shape(connectome.getNeuronCount(), 1))) {
			a(cp(spikes).each(), c(1.0)).get().run();
			Temporal product = () -> a("sparse-product", cp(input), matmul(cp(connectome.getWeights()), cp(spikes)).reshape(input.getShape()));
			SimulationRunner runner = new SimulationRunner(product, ConnectomeNetwork.DT);

			try {
				runner.run(WARMUP);
				long start = System.nanoTime();
				runner.run(STEPS);
				double micros = (System.nanoTime() - start) / 1000.0 / STEPS;
				double total = DoubleStream.of(input.toArray()).sum();

				log("backend=" + Hardware.getLocalHardware().getDataContext().getName()
						+ " sparseProductMicros=" + micros + " inputSum=" + total);
				Assert.assertTrue("non-finite synaptic input " + total, Double.isFinite(total));
				Assert.assertTrue("the connectome has no stored synapse", total != 0.0);
			} finally {
				runner.destroy();
			}
		}
	}

	/**
	 * Logs the number of elements and bytes held by the tensors of a network: the compressed
	 * connectome (one stored value per edge, not the {@code N * N} of the weight matrix's
	 * logical shape), the delay line, the neuron state and the Poisson targets.
	 *
	 * @param network the network
	 */
	protected void logFootprint(ConnectomeNetwork network) {
		NeuronConnectome connectome = network.getConnectome();
		ExponentialSynapseNeurons neurons = network.getNeurons();
		long connectomeElements = connectome.getEdgeCount() + elements(connectome.getOrdering().getRowOffsets(),
				connectome.getOrdering().getColumns(), connectome.getSigns());
		long ringElements = elements(network.getRing());
		long neuronElements = elements(neurons.getMembrane(), neurons.getSynapse(), neurons.getRefractory(),
				neurons.getSpikes(), neurons.getSpikeCounts());
		long total = connectomeElements + ringElements + neuronElements + 2L * connectome.getNeuronCount();
		int bytes = Hardware.getLocalHardware().getPrecision().bytes();

		log("connectomeElements=" + connectomeElements + " ringElements=" + ringElements
				+ " ringShape=" + network.getRing().getShape()
				+ " neuronElements=" + neuronElements + " totalElements=" + total
				+ " totalMegabytes=" + total * bytes / 1e6);
	}

	/**
	 * Returns the number of elements of memory held by some collections.
	 *
	 * @param collections the collections
	 * @return the sum of their memory lengths
	 */
	private static long elements(PackedCollection... collections) {
		return Stream.of(collections).mapToLong(PackedCollection::getMemLength).sum();
	}
}
