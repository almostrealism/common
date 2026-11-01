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

import org.almostrealism.bioscience.connectome.NeuronConnectome;
import org.almostrealism.collect.PackedCollection;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

/**
 * {@link NeuronConnectome} on the synthetic connectome committed under
 * {@code src/test/resources/connectome/synthetic}, written by
 * {@code connectome_csr.py --dataset synthetic} through the same path as the real datasets: 40
 * neurons, body id {@code 1000 + 7 i}, type {@code T(i mod 5)}, every third neuron inhibitory.
 * Needs no dataset, so it runs everywhere.
 */
public class NeuronConnectomeTest extends NeuronConnectomeChecks {
	/** The number of neurons of the synthetic connectome. */
	private static final int NEURONS = 40;

	/** The loaded counts and totals agree with the metadata the script wrote. */
	@Test(timeout = 120000)
	public void totals() throws IOException {
		assertTotals(synthetic());
	}

	/** Every spot-checked neuron agrees with the script's recomputation from the edge list. */
	@Test(timeout = 300000)
	public void spotChecks() throws IOException {
		assertSpotChecks(synthetic());
	}

	/**
	 * The committed FlyWire sugar circuit, a real connectome cut from the 630 snapshot, loads with
	 * the totals the script recorded.
	 */
	@Test(timeout = 120000)
	public void sugarCircuitTotals() throws IOException {
		assertTotals(sugarCircuit());
	}

	/**
	 * The sugar circuit's spot checks, including MN9 and a sugar GRN, agree with the script's
	 * recomputation from the FlyWire source table, restricted to the circuit's neurons.
	 */
	@Test(timeout = 300000)
	public void sugarCircuitSpotChecks() throws IOException {
		assertSpotChecks(sugarCircuit());
	}

	/**
	 * The sparse product of the loaded matrix with a vector equals the product of the dense
	 * matrix, read element by element through the ordering, with the same vector.
	 */
	@Test(timeout = 120000)
	public void sparseProductMatchesDense() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(synthetic());
			 PackedCollection x = integers(0, NEURONS).mod(7).subtract(3.0).evaluate();
			 PackedCollection dense = cp(connectome.getWeights()).multiply(1.0).evaluate();
			 PackedCollection y = matmul(cp(connectome.getWeights()), cp(x)).evaluate()) {
			double[] matrix = dense.toArray();
			double[] input = x.toArray();
			double[] result = y.toArray();
			assertEquals(NEURONS * NEURONS, matrix.length);
			assertEquals(connectome.getEdgeCount(),
					IntStream.range(0, matrix.length).filter(i -> matrix[i] != 0.0).count());

			for (int i = 0; i < NEURONS; i++) {
				int row = i;
				double expected = IntStream.range(0, NEURONS).mapToDouble(j -> matrix[row * NEURONS + j] * input[j]).sum();
				assertEquals("row " + i, expected, result[i], 0.0);
			}
		}
	}

	/**
	 * Body ids, types, transmitters and signs are looked up by index and by body id, and a mask
	 * selects exactly the given neurons.
	 */
	@Test(timeout = 120000)
	public void lookups() throws IOException {
		try (NeuronConnectome connectome = NeuronConnectome.load(synthetic());
			 PackedCollection mask = connectome.mask(3, 17, 39).evaluate()) {
			assertEquals(NEURONS, connectome.getNeuronCount());
			for (int i = 0; i < NEURONS; i++) {
				assertEquals(1000 + 7L * i, connectome.getBodyId(i));
				assertEquals(i, connectome.indexOf(1000 + 7L * i));
				assertEquals("T" + (i % 5), connectome.getType(i));
				assertEquals(i % 3 == 2 ? "gaba" : "acetylcholine", connectome.getTransmitter(i));
				assertEquals(i % 3 == 2 ? -1.0 : 1.0, connectome.getSigns().toDouble(i), 0.0);
			}

			Assert.assertArrayEquals(new int[] { 1, 6, 11, 16, 21, 26, 31, 36 }, connectome.indicesOfType("T1"::equals));
			Assert.assertArrayEquals(new int[] { 2, 0 }, connectome.indicesOf(1014, 1000));
			assertRejected(() -> connectome.indexOf(1001));
			assertRejected(() -> connectome.mask(3, 3));
			assertRejected(() -> connectome.mask(NEURONS));

			double[] values = mask.toArray();
			assertEquals(NEURONS, values.length);
			for (int i = 0; i < NEURONS; i++) {
				assertEquals("mask " + i, i == 3 || i == 17 || i == 39 ? 1.0 : 0.0, values[i], 0.0);
			}
		}
	}

	/** A neuron table without the expected header is rejected before any tensor is loaded. */
	@Test(timeout = 60000)
	public void rejectsMalformedNeuronTable() throws IOException {
		Path directory = Files.createTempDirectory("connectome");
		try {
			Files.copy(new File(synthetic(), NeuronConnectome.TENSORS_FILE).toPath(),
					directory.resolve(NeuronConnectome.TENSORS_FILE));
			Files.write(directory.resolve(NeuronConnectome.NEURONS_FILE),
					"id\ttype\n1\tA\n".getBytes(StandardCharsets.UTF_8));
			try {
				NeuronConnectome.load(directory.toFile()).destroy();
				Assert.fail("A malformed neuron table was accepted");
			} catch (IllegalArgumentException expected) {
				log("rejected=" + expected.getMessage());
			}
		} finally {
			Files.delete(directory.resolve(NeuronConnectome.NEURONS_FILE));
			Files.delete(directory.resolve(NeuronConnectome.TENSORS_FILE));
			Files.delete(directory);
		}
	}

	/**
	 * Asserts that an operation is rejected with an {@link IllegalArgumentException}.
	 *
	 * @param operation the operation
	 */
	private void assertRejected(Runnable operation) {
		try {
			operation.run();
			Assert.fail("The operation was accepted");
		} catch (IllegalArgumentException expected) {
			log("rejected=" + expected.getMessage());
		}
	}
}
