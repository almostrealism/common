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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.bioscience.connectome.NeuronConnectome;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * The checks a connectome written by {@code studio/bioscience/scripts/connectome_csr.py} must
 * pass once loaded by {@link NeuronConnectome}, shared by the test on the committed synthetic
 * connectome and the tests on the full datasets.
 *
 * <p>The incoming side of a neuron is read from the compressed rows on the host, through a
 * separate {@link StateDictionary} over the same files. The outgoing side is a column of the
 * matrix, computed on the device by the sparse product of the loaded matrix with the neuron's
 * mask. Both are compared with {@code spot-checks.json}, which the script recomputed from the
 * source table by body id, and the totals with {@code connectome.json}.</p>
 */
public abstract class NeuronConnectomeChecks extends TestSuiteBase {
	/** The metadata file written by the conversion script. */
	public static final String METADATA_FILE = "connectome.json";

	/** The spot-check file written by the conversion script. */
	public static final String SPOT_CHECKS_FILE = "spot-checks.json";

	/**
	 * Returns the directory of a file of the test resources.
	 *
	 * @param resource the classpath path of a file in the directory
	 * @return the directory
	 */
	protected File resourceDirectory(String resource) {
		try {
			return new File(getClass().getResource(resource).toURI()).getParentFile();
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The directory under which converted datasets live by default. */
	public static final String DERIVED = "/Users/Shared/models-derived";

	/** The property or environment variable naming the converted MaleCNS directory. */
	public static final String MALE_CNS_PROPERTY = "AR_MALE_CNS_CSR";

	/** The property or environment variable naming the converted FlyWire 630 directory. */
	public static final String FLYWIRE_630_PROPERTY = "AR_FLYWIRE_630_CSR";

	/** The property or environment variable naming the converted FlyWire 783 directory. */
	public static final String FLYWIRE_783_PROPERTY = "AR_FLYWIRE_783_CSR";

	/**
	 * Returns the directory of a converted connectome kept outside the repository, requiring it
	 * on every host: a test that asks for a full dataset runs only when someone asks for it (they
	 * are long-running and excluded from the pipeline profile), so its absence is an error, never
	 * a skip.
	 *
	 * @param property         the property or environment variable naming the directory
	 * @param defaultDirectory the directory used when it is not set
	 * @return the directory
	 */
	protected File requireConnectome(String property, String defaultDirectory) {
		return requireResourceIn(property, defaultDirectory, NeuronConnectome.TENSORS_FILE,
				ComputeRequirement.GPU, ComputeRequirement.CPU);
	}

	/**
	 * Returns the converted MaleCNS directory, failing the test when it is absent.
	 *
	 * @return the directory
	 */
	protected File maleCns() {
		return requireConnectome(MALE_CNS_PROPERTY, DERIVED + "/male-cns/csr");
	}

	/**
	 * Returns the converted FlyWire 630 directory, failing the test when it is absent.
	 *
	 * @return the directory
	 */
	protected File flywire630() {
		return requireConnectome(FLYWIRE_630_PROPERTY, DERIVED + "/flywire/csr-630");
	}

	/**
	 * Returns the converted FlyWire 783 directory, failing the test when it is absent.
	 *
	 * @return the directory
	 */
	protected File flywire783() {
		return requireConnectome(FLYWIRE_783_PROPERTY, DERIVED + "/flywire/csr-783");
	}

	/**
	 * Returns the directory of the committed synthetic connectome, written by
	 * {@code connectome_csr.py --dataset synthetic}.
	 *
	 * @return the directory
	 */
	protected File synthetic() {
		return resourceDirectory("/connectome/synthetic/" + NeuronConnectome.TENSORS_FILE);
	}

	/**
	 * Returns the directory of the committed FlyWire 630 sugar circuit, written by
	 * {@code brian2_reference.py circuit}: the 451 neurons that fire in the Brian2 sugar
	 * experiment at 200 Hz, including the 21 sugar GRNs and MN9, with every edge joining two of
	 * them.
	 *
	 * @return the directory
	 */
	protected File sugarCircuit() {
		return resourceDirectory("/connectome/sugar-circuit/" + NeuronConnectome.TENSORS_FILE);
	}

	/**
	 * Reads a JSON file of a connectome directory.
	 *
	 * @param directory the connectome directory
	 * @param name      the file name
	 * @return the parsed JSON
	 * @throws IOException if the file cannot be read
	 */
	protected static JsonElement json(File directory, String name) throws IOException {
		try (Reader reader = Files.newBufferedReader(new File(directory, name).toPath(), StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader);
		}
	}

	/**
	 * Checks the loaded neuron and edge counts and the synapse and inhibitory-edge totals of the
	 * stored entries against {@code connectome.json}.
	 *
	 * @param directory the connectome directory
	 * @throws IOException if a file cannot be read
	 */
	protected void assertTotals(File directory) throws IOException {
		JsonObject metadata = json(directory, METADATA_FILE).getAsJsonObject();

		try (NeuronConnectome connectome = NeuronConnectome.load(directory);
			 StateDictionary raw = new StateDictionary(directory.getPath())) {
			assertEquals(metadata.get("neurons").getAsInt(), connectome.getNeuronCount());
			assertEquals(metadata.get("edges").getAsInt(), connectome.getEdgeCount());

			int[] weights = raw.require("weights").toIntArray();
			assertEquals(metadata.get("synapses").getAsLong(),
					IntStream.of(weights).mapToLong(Math::abs).sum());
			assertEquals(metadata.get("inhibitory_edges").getAsLong(),
					IntStream.of(weights).filter(w -> w < 0).count());
			log("dataset=" + metadata.get("dataset").getAsString() + " neurons="
					+ connectome.getNeuronCount() + " edges=" + connectome.getEdgeCount());
		}
	}

	/**
	 * Checks every spot-checked neuron: its index and body id, its in-degree, incoming synapses
	 * and strongest presynaptic partners from the compressed rows, and its out-degree, outgoing
	 * synapses and sign from the column computed on the device. The column comes from a sparse
	 * product over the whole matrix, so on a dataset with more than 2^24 entries it reads entries
	 * whose positions are beyond the range FP32 holds exactly.
	 *
	 * @param directory the connectome directory
	 * @throws IOException if a file cannot be read
	 */
	protected void assertSpotChecks(File directory) throws IOException {
		JsonArray checks = json(directory, SPOT_CHECKS_FILE).getAsJsonArray();
		assertTrue(checks.size() > 0);

		try (NeuronConnectome connectome = NeuronConnectome.load(directory);
			 StateDictionary raw = new StateDictionary(directory.getPath())) {
			int[] offsets = raw.require("row_offsets").toIntArray();
			int[] presynaptic = raw.require("presynaptic").toIntArray();
			int[] weights = raw.require("weights").toIntArray();
			double[] signs = connectome.getSigns().toArray();

			for (JsonElement element : checks) {
				JsonObject check = element.getAsJsonObject();
				int index = check.get("index").getAsInt();
				assertEquals(index, connectome.indexOf(check.get("body_id").getAsLong()));
				assertEquals(check.get("body_id").getAsLong(), connectome.getBodyId(index));

				int start = offsets[index];
				int end = offsets[index + 1];
				assertEquals(check.toString(), check.get("in_degree").getAsInt(), end - start);
				assertEquals(check.toString(), check.get("in_synapses").getAsLong(),
						IntStream.range(start, end).mapToLong(k -> Math.abs(weights[k])).sum());

				int[] strongest = IntStream.range(start, end).boxed()
						.sorted(Comparator.<Integer>comparingInt(k -> -Math.abs(weights[k]))
								.thenComparingLong(k -> connectome.getBodyId(presynaptic[k])))
						.mapToInt(Integer::intValue).toArray();
				JsonArray inputs = check.getAsJsonArray("strongest_inputs");
				for (int p = 0; p < inputs.size(); p++) {
					JsonObject input = inputs.get(p).getAsJsonObject();
					assertEquals(check.toString(), input.get("body_id").getAsLong(),
							connectome.getBodyId(presynaptic[strongest[p]]));
					assertEquals(check.toString(), input.get("weight").getAsInt(), weights[strongest[p]]);
				}

				try (PackedCollection column = matmul(cp(connectome.getWeights()), connectome.mask(index)).evaluate()) {
					double[] values = column.toArray();
					assertEquals(connectome.getNeuronCount(), values.length);
					assertEquals(check.toString(), check.get("out_degree").getAsInt(),
							IntStream.range(0, values.length).filter(i -> values[i] != 0.0).count());
					assertEquals(check.toString(), check.get("out_synapses").getAsDouble(),
							IntStream.range(0, values.length).mapToDouble(i -> Math.abs(values[i])).sum(), 0.0);
					assertTrue(check.toString(), IntStream.range(0, values.length)
							.allMatch(i -> values[i] * signs[index] >= 0.0));
				}
			}

			log("spotChecks=" + checks.size());
		}
	}
}
