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

import org.almostrealism.bioscience.connectome.ConnectomeNetwork;
import org.almostrealism.bioscience.connectome.NeuronConnectome;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The sugar-sensing experiment of Shiu et al. (2024) on the FlyWire 630 connectome, compared with
 * the authors' Brian2 model. Poisson input drives the 21 sugar gustatory receptor neurons for
 * 1 s, 30 trials (five passes of six replicas); the mean rate of every neuron must agree with the reference written by
 * {@code brian2_reference.py sugar} within the spread of two independent Brian2 runs:
 * <ul>
 *   <li>MN9, the proboscis motor neuron the experiment is known for, within 5%;</li>
 *   <li>over every neuron above 10 Hz in either run, a median relative difference of at most 3%
 *       and a 90th percentile of at most 8%;</li>
 *   <li>at least 48 of the 50 fastest neurons in common.</li>
 * </ul>
 * <p>As a negative control, the same input to 21 neurons spread evenly over the connectome leaves
 * MN9 silent.</p>
 *
 * <p>The connectome and the reference live outside the repository, by default under
 * {@value NeuronConnectomeDatasetTest#DERIVED}, and each test is skipped where they are absent.</p>
 */
public class ConnectomeNetworkDatasetTest extends NeuronConnectomeChecks {
	/** The property or environment variable naming the directory of the Brian2 references. */
	public static final String REFERENCE_PROPERTY = "AR_FLYWIRE_BRIAN2_REFERENCE";

	/** The FlyWire root id of MN9. */
	public static final long MN9 = 720575940660219265L;

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

	/**
	 * The number of trials simulated at once: the most replicas of FlyWire 630 whose delay line
	 * fits {@link ConnectomeNetwork#DELAY_LINE_LIMIT}, and a divisor of {@link #TRIALS}.
	 */
	public static final int REPLICAS = 6;

	/** The number of steps of one trial: 1 s. */
	public static final int STEPS = 10000;

	/**
	 * Returns the directory of a Brian2 reference, skipping the calling test when it is absent.
	 *
	 * @param rate the Poisson rate of the experiment in hertz
	 * @return the directory
	 */
	private File reference(int rate) {
		File directory = new File(SystemUtils.getProperty(REFERENCE_PROPERTY,
				NeuronConnectomeDatasetTest.DERIVED + "/flywire/brian2-reference"), "sugarR_630_" + rate + "Hz");
		Assume.assumeTrue(new File(directory, "rates.safetensors").exists());
		return directory;
	}

	/** Sugar input at 100 Hz agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	public void sugar100Hz() throws IOException {
		assertSugarMatchesBrian2(100);
	}

	/** Sugar input at 150 Hz, the model's default rate, agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	public void sugar150Hz() throws IOException {
		assertSugarMatchesBrian2(150);
	}

	/** Sugar input at 200 Hz agrees with Brian2. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	public void sugar200Hz() throws IOException {
		assertSugarMatchesBrian2(200);
	}

	/** The same input to 21 neurons spread evenly over the connectome leaves MN9 silent. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	public void nonGustatoryInputLeavesMn9Silent() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(NeuronConnectomeDatasetTest.flywire630())) {
			int n = connectome.getNeuronCount();
			Set<Integer> excluded = new HashSet<>();
			for (int index : connectome.indicesOf(SUGAR)) excluded.add(index);
			int[] control = IntStream.range(0, 21).map(i -> (int) ((i + 0.5) * n / 21))
					.filter(i -> !excluded.contains(i)).toArray();

			ConnectomeNetwork network = new ConnectomeNetwork(connectome, REPLICAS, 150.0, 0, control);
			double[] rates = network.meanRates(TRIALS, STEPS).evaluate().toArray();
			double mn9 = rates[connectome.indexOf(MN9)];
			log("controlNeurons=" + control.length + " mn9Rate=" + mn9);
			Assert.assertEquals(0.0, mn9, 0.5);
			network.destroy();
		}
	}

	/**
	 * Runs the sugar experiment at a rate and compares the mean rate of every neuron with the
	 * Brian2 reference.
	 *
	 * @param rate the Poisson rate in hertz
	 * @throws IOException if the connectome or the reference cannot be read
	 */
	protected void assertSugarMatchesBrian2(int rate) throws IOException {
		File reference = reference(rate);
		try (NeuronConnectome connectome = NeuronConnectome.load(NeuronConnectomeDatasetTest.flywire630());
			 StateDictionary brian = new StateDictionary(reference.getPath())) {
			double[] expected = brian.get("rates").toArray();
			Assert.assertEquals(connectome.getNeuronCount(), expected.length);

			ConnectomeNetwork network = new ConnectomeNetwork(connectome, REPLICAS, rate, 0, connectome.indicesOf(SUGAR));
			long start = System.currentTimeMillis();
			double[] actual = network.meanRates(TRIALS, STEPS).evaluate().toArray();
			log("rate=" + rate + " simulatedSeconds=" + TRIALS + " wallSeconds="
					+ (System.currentTimeMillis() - start) / 1000.0);
			network.destroy();

			assertRatesAgree(actual, expected, connectome.indexOf(MN9));
		}
	}

	/**
	 * Asserts that two sets of mean rates agree within the tolerance of this test.
	 *
	 * @param actual   the rates of the network
	 * @param expected the rates of the reference
	 * @param mn9      the index of MN9
	 */
	protected void assertRatesAgree(double[] actual, double[] expected, int mn9) {
		double[] relative = IntStream.range(0, expected.length)
				.filter(i -> actual[i] > 10 || expected[i] > 10)
				.mapToDouble(i -> Math.abs(actual[i] - expected[i]) / Math.max(actual[i], expected[i]))
				.sorted().toArray();
		double median = relative[relative.length / 2];
		double p90 = relative[(int) (0.9 * (relative.length - 1))];
		Set<Integer> fastestActual = fastest(actual, 50);
		Set<Integer> fastestExpected = fastest(expected, 50);
		fastestActual.retainAll(fastestExpected);

		log("mn9=" + actual[mn9] + " mn9Reference=" + expected[mn9] + " neuronsAbove10Hz=" + relative.length
				+ " medianRelative=" + median + " p90Relative=" + p90 + " top50Overlap=" + fastestActual.size());
		Assert.assertEquals("MN9", expected[mn9], actual[mn9], 0.05 * expected[mn9]);
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
