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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.bioscience.connectome.ConnectomeNetwork;
import org.almostrealism.bioscience.connectome.NeuronConnectome;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * The sugar-sensing experiment of Shiu et al. (2024) on whole-brain connectomes, compared with the
 * authors' Brian2 model. Each experiment is the one its Brian2 reference's manifest describes:
 * Poisson input at a rate to the stimulated neurons, a number of trials of a duration (all trials
 * in one pass, one replica each) and a synapse weight. The mean rate of every neuron must agree
 * with the reference within the spread of two independent Brian2 runs:
 * <ul>
 *   <li>MN9, the proboscis motor neuron the experiment is known for, within 5%;</li>
 *   <li>over every neuron above 10 Hz in either run, a median relative difference of at most 3%
 *       and a 90th percentile of at most 8%;</li>
 *   <li>at least 48 of the 50 fastest neurons in common.</li>
 * </ul>
 * <p>As a negative control, the same input to as many neurons spread evenly over the connectome
 * leaves MN9 silent.</p>
 *
 * <p>On FlyWire 630 the references are written by {@code brian2_reference.py sugar}, which drives
 * the 21 sugar gustatory receptor neurons of the paper. On MaleCNS they are written by
 * {@code brian2_reference.py experiment --name male-cns_<rate>Hz}, which runs the same model on
 * the converted connectome and records the neurons it was asked to drive.</p>
 *
 * <p>The connectomes and the references live outside the repository, by default under
 * {@value NeuronConnectomeChecks#DERIVED}. An experiment on FlyWire 630 takes about 8 minutes, so
 * these tests are long-running and excluded from the pipeline profile: they run when asked for,
 * and then missing data fails the test on every host. The pipeline runs the same network on the
 * committed sugar circuit, the neurons the FlyWire experiment activates, against Brian2 spike for
 * spike ({@link ConnectomeNetworkTest}).</p>
 */
public class ConnectomeNetworkDatasetTest extends NeuronConnectomeChecks {
	/** The property or environment variable naming the directory of the FlyWire Brian2 references. */
	public static final String REFERENCE_PROPERTY = "AR_FLYWIRE_BRIAN2_REFERENCE";

	/** The property or environment variable naming the directory of the MaleCNS Brian2 references. */
	public static final String MALE_CNS_REFERENCE_PROPERTY = "AR_MALE_CNS_BRIAN2_REFERENCE";

	/** The FlyWire root id of MN9. */
	public static final long MN9 = 720575940660219265L;

	/** The MaleCNS body ids of the two MN9s, one on each side. */
	public static final long[] MALE_CNS_MN9 = { 10331L, 16949L };

	/** The FlyWire root ids of the 21 sugar gustatory receptor neurons. */
	public static final long[] SUGAR = {
			720575940624963786L, 720575940630233916L, 720575940637568838L, 720575940638202345L,
			720575940617000768L, 720575940630797113L, 720575940632889389L, 720575940621754367L,
			720575940621502051L, 720575940640649691L, 720575940639332736L, 720575940616885538L,
			720575940639198653L, 720575940620900446L, 720575940617937543L, 720575940632425919L,
			720575940633143833L, 720575940612670570L, 720575940628853239L, 720575940629176663L,
			720575940611875570L
	};

	/** The number of trials of the experiment. */
	public static final int TRIALS = 30;

	/** The number of trials simulated at once: every trial of the experiment in one pass. */
	public static final int REPLICAS = TRIALS;

	/** The number of steps of the replica comparison. */
	public static final int REPLICA_STEPS = 400;

	/** The number of steps of one trial: 1 s. */
	public static final int STEPS = 10000;

	/**
	 * Returns the directory of a FlyWire 630 Brian2 reference, failing the calling test when it is
	 * absent.
	 *
	 * @param rate the Poisson rate of the experiment in hertz
	 * @return the directory
	 */
	private File reference(int rate) {
		return reference(REFERENCE_PROPERTY, DERIVED + "/flywire/brian2-reference", "sugarR_630_" + rate + "Hz");
	}

	/**
	 * Returns the directory of a MaleCNS Brian2 reference, failing the calling test when it is
	 * absent.
	 *
	 * @param rate the Poisson rate of the experiment in hertz
	 * @return the directory
	 */
	private File maleCnsReference(int rate) {
		return reference(MALE_CNS_REFERENCE_PROPERTY, DERIVED + "/male-cns/brian2-reference", "male-cns_" + rate + "Hz");
	}

	/**
	 * Returns the directory of one Brian2 reference, failing the calling test when it is absent.
	 *
	 * @param property         the property naming the directory of the references
	 * @param defaultDirectory the directory of the references when the property is not set
	 * @param experiment       the name of the experiment, the reference's directory
	 * @return the directory
	 */
	private File reference(String property, String defaultDirectory, String experiment) {
		return new File(requireResourceIn(property, defaultDirectory, experiment + "/rates.safetensors",
				ComputeRequirement.GPU, ComputeRequirement.CPU), experiment);
	}

	/** Sugar input at 100 Hz agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void sugar100Hz() throws IOException {
		assertMatchesBrian2(flywire630(), reference(100), MN9);
	}

	/** Sugar input at 150 Hz, the model's default rate, agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void sugar150Hz() throws IOException {
		assertMatchesBrian2(flywire630(), reference(150), MN9);
	}

	/** Sugar input at 200 Hz agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void sugar200Hz() throws IOException {
		assertMatchesBrian2(flywire630(), reference(200), MN9);
	}

	/** The same input to 21 neurons spread evenly over FlyWire 630 leaves MN9 silent. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void nonGustatoryInputLeavesMn9Silent() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(flywire630())) {
			assertControlLeavesMn9Silent(connectome, SUGAR.length, ConnectomeNetwork.SYNAPSE_WEIGHT,
					connectome.indicesOf(SUGAR), MN9);
		}
	}

	/** MaleCNS under the input of its 100 Hz reference agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void maleCns100Hz() throws IOException {
		assertMatchesBrian2(maleCns(), maleCnsReference(100), MALE_CNS_MN9);
	}

	/** MaleCNS under the input of its 150 Hz reference agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void maleCns150Hz() throws IOException {
		assertMatchesBrian2(maleCns(), maleCnsReference(150), MALE_CNS_MN9);
	}

	/** MaleCNS under the input of its 200 Hz reference agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void maleCns200Hz() throws IOException {
		assertMatchesBrian2(maleCns(), maleCnsReference(200), MALE_CNS_MN9);
	}

	/**
	 * Replica 0 of a network of {@link #REPLICAS} replicas of FlyWire 630 under sugar input emits
	 * exactly the spikes of a network of one replica. The two draw the same Poisson kicks for
	 * replica 0, so any difference is replicas leaking into one another. The delay line of the
	 * larger network holds 72.6M elements (logged), past the {@code 2^24} below which a single-precision
	 * backend represents every integer, so this also requires the delay line to be addressed
	 * exactly.
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void replicasMatchOneReplica() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(flywire630())) {
			int n = connectome.getNeuronCount();
			double[] single = spikeCounts(connectome, 1);
			double[] batch = spikeCounts(connectome, REPLICAS);

			int active = (int) IntStream.range(0, n).filter(i -> single[i] > 0).count();
			int[] differing = IntStream.range(0, n).filter(i -> single[i] != batch[i]).toArray();
			log("activeNeurons=" + active + " differingNeurons=" + differing.length);
			Assert.assertTrue("only " + active + " neurons fired", active > 100);
			Assert.assertEquals("neurons whose replica-0 spike count differs from one replica's, first "
					+ Arrays.toString(Arrays.copyOf(differing, Math.min(10, differing.length))), 0, differing.length);
		}
	}

	/**
	 * Runs {@link #REPLICA_STEPS} steps of sugar input at 200 Hz on a network of a number of
	 * replicas and returns the spike counts of the neurons of replica 0.
	 *
	 * @param connectome the connectome
	 * @param replicas   the number of replicas
	 * @return the spike count of each neuron of replica 0
	 */
	private double[] spikeCounts(NeuronConnectome connectome, int replicas) {
		int n = connectome.getNeuronCount();
		ConnectomeNetwork network = new ConnectomeNetwork(connectome, replicas, 200.0, 0,
				connectome.indicesOf(SUGAR));
		log("replicas=" + replicas + " delayLineElements=" + network.getRing().getShape().getTotalSizeLong());
		network.getRunner().run(REPLICA_STEPS);
		double[] counts = Arrays.copyOf(network.getNeurons().getSpikeCounts().toArray(), n);
		network.destroy();
		return counts;
	}

	/**
	 * The input of the 150 Hz MaleCNS reference, given instead to as many neurons spread evenly
	 * over the connectome and outside the gustatory class, leaves both MN9s silent.
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void maleCnsNonGustatoryInputLeavesMn9Silent() throws IOException {
		JsonObject manifest = json(maleCnsReference(150), "manifest.json").getAsJsonObject();
		try (NeuronConnectome connectome = NeuronConnectome.load(maleCns())) {
			int[] gustatory = IntStream.range(0, connectome.getNeuronCount())
					.filter(i -> "gustatory".equals(connectome.getNeuronClass(i))).toArray();
			assertControlLeavesMn9Silent(connectome, manifest.getAsJsonArray("stimulated_indices").size(),
					synapseWeight(manifest), gustatory, MALE_CNS_MN9);
		}
	}

	/**
	 * Gives 150 Hz Poisson input to neurons spread evenly over a connectome, other than some
	 * excluded neurons, and asserts that MN9 stays silent.
	 *
	 * @param connectome    the connectome
	 * @param count         the number of positions the control neurons are spread over
	 * @param synapseWeight the synaptic potential of one synapse
	 * @param excluded      the indices of neurons the control must not drive
	 * @param mn9           the body ids of MN9
	 */
	protected void assertControlLeavesMn9Silent(NeuronConnectome connectome, int count, double synapseWeight,
												int[] excluded, long... mn9) {
		int n = connectome.getNeuronCount();
		Set<Integer> skip = new HashSet<>();
		for (int index : excluded) skip.add(index);
		int[] control = IntStream.range(0, count).map(i -> (int) ((i + 0.5) * n / count))
				.filter(i -> !skip.contains(i)).toArray();

		ConnectomeNetwork network = new ConnectomeNetwork(connectome, null, synapseWeight, REPLICAS, 150.0, 0, control);
		double[] rates = network.meanRates(TRIALS, STEPS).evaluate().toArray();
		network.destroy();
		for (int index : connectome.indicesOf(mn9)) {
			log("controlNeurons=" + control.length + " mn9Rate=" + rates[index]);
			Assert.assertEquals(0.0, rates[index], 0.5);
		}
	}

	/**
	 * Runs the experiment a Brian2 reference describes and compares the mean rate of every neuron
	 * with the reference's. The manifest gives the Poisson rate, the stimulated neurons (whose body
	 * ids must be the connectome's at those indices), the number and length of the trials, and the
	 * synapse weight (the model's when the manifest predates recording it). Every trial runs at
	 * once, one replica each.
	 *
	 * @param directory the connectome directory
	 * @param reference the directory of the Brian2 reference
	 * @param recorded  the body ids of the neurons whose rate must agree within 5% (MN9)
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	protected void assertMatchesBrian2(File directory, File reference, long... recorded) throws IOException {
		JsonObject manifest = json(reference, "manifest.json").getAsJsonObject();
		double rate = manifest.get("rate_hz").getAsDouble();
		int trials = manifest.get("trials").getAsInt();
		int steps = (int) Math.round(manifest.get("duration_s").getAsDouble() * 1000 / ConnectomeNetwork.DT);
		int[] stimulated = StreamSupport.stream(manifest.getAsJsonArray("stimulated_indices").spliterator(), false)
				.mapToInt(JsonElement::getAsInt).toArray();
		long[] stimulatedIds = StreamSupport.stream(manifest.getAsJsonArray("stimulated").spliterator(), false)
				.mapToLong(e -> Long.parseLong(e.getAsString())).toArray();

		try (NeuronConnectome connectome = NeuronConnectome.load(directory);
			 StateDictionary brian = new StateDictionary(reference.getPath())) {
			double[] expected = brian.get("rates").toArray();
			Assert.assertEquals(connectome.getNeuronCount(), expected.length);
			Assert.assertArrayEquals("stimulated body ids", stimulatedIds,
					IntStream.of(stimulated).mapToLong(connectome::getBodyId).toArray());

			ConnectomeNetwork network = new ConnectomeNetwork(connectome, null, synapseWeight(manifest),
					trials, rate, 0, stimulated);
			long start = System.currentTimeMillis();
			double[] actual = network.meanRates(trials, steps).evaluate().toArray();
			log("experiment=" + manifest.get("experiment").getAsString() + " stimulated=" + stimulated.length
					+ " simulatedSeconds=" + trials * steps * ConnectomeNetwork.DT / 1000
					+ " wallSeconds=" + (System.currentTimeMillis() - start) / 1000.0);
			network.destroy();

			assertRatesAgree(actual, expected, connectome.indicesOf(recorded));
		}
	}

	/**
	 * Returns the synapse weight a Brian2 reference ran with: the recorded one, or the model's for
	 * a manifest that predates recording it.
	 *
	 * @param manifest the reference's manifest
	 * @return the synaptic potential of one synapse in millivolts
	 */
	private static double synapseWeight(JsonObject manifest) {
		return manifest.has("synapse_mv") ? manifest.get("synapse_mv").getAsDouble() : ConnectomeNetwork.SYNAPSE_WEIGHT;
	}

	/**
	 * Asserts that two sets of mean rates agree within the tolerance of this test.
	 *
	 * @param actual   the rates of the network
	 * @param expected the rates of the reference
	 * @param mn9      the indices of MN9
	 */
	protected void assertRatesAgree(double[] actual, double[] expected, int... mn9) {
		double[] relative = IntStream.range(0, expected.length)
				.filter(i -> actual[i] > 10 || expected[i] > 10)
				.mapToDouble(i -> Math.abs(actual[i] - expected[i]) / Math.max(actual[i], expected[i]))
				.sorted().toArray();
		double median = relative[relative.length / 2];
		double p90 = relative[(int) (0.9 * (relative.length - 1))];
		Set<Integer> fastestActual = fastest(actual, 50);
		Set<Integer> fastestExpected = fastest(expected, 50);
		fastestActual.retainAll(fastestExpected);

		log("neuronsAbove10Hz=" + relative.length + " medianRelative=" + median + " p90Relative=" + p90
				+ " top50Overlap=" + fastestActual.size());
		for (int index : mn9) {
			log("mn9=" + actual[index] + " mn9Reference=" + expected[index]);
			Assert.assertEquals("MN9 (neuron " + index + ")", expected[index], actual[index], 0.05 * expected[index]);
		}
		Assert.assertTrue("median relative difference " + median, median <= 0.03);
		Assert.assertTrue("90th percentile relative difference " + p90, p90 <= 0.08);
		Assert.assertTrue("top-50 overlap " + fastestActual.size(), fastestActual.size() >= 48);
	}

	/**
	 * Returns the indices of the fastest neurons.
	 *
	 * @param rates the rate of every neuron
	 * @param count the number of neurons to return
	 * @return the indices of the {@code count} highest rates
	 */
	private static Set<Integer> fastest(double[] rates, int count) {
		Integer[] order = IntStream.range(0, rates.length).boxed().toArray(Integer[]::new);
		Arrays.sort(order, Comparator.comparingDouble(i -> -rates[i]));
		return new HashSet<>(Arrays.asList(order).subList(0, count));
	}
}
