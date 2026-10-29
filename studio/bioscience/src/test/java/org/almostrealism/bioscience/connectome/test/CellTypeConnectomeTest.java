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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.bioscience.connectome.CellTypeConnectome;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Checks the MaleCNS cell-type matrix written by {@code studio/bioscience/scripts/male_cns_cell_types.py}
 * and loaded by {@link CellTypeConnectome}: the loaded entries against values the script
 * recomputed from the raw edge table by a separate path, and a device {@code matmul} over the
 * whole matrix against its host columns.
 *
 * <p>The data lives outside the repository. Its directory is the {@value #DIRECTORY_PROPERTY}
 * system property or environment variable, by default {@value #DEFAULT_DIRECTORY}. These are new
 * tests that need that directory, so each one is skipped where it is absent and runs on the
 * nodes that provide it.</p>
 */
public class CellTypeConnectomeTest extends TestSuiteBase {
	/** The property or environment variable naming the data directory. */
	public static final String DIRECTORY_PROPERTY = "AR_MALE_CNS_CELL_TYPES";

	/** The data directory used when the property is not set. */
	public static final String DEFAULT_DIRECTORY = "/Users/Shared/models-derived/male-cns/cell-type-matrix";

	/** The file of recomputed entries written by the conversion script. */
	private static final String SPOT_CHECKS_FILE = "spot-checks.json";

	/**
	 * Returns the data directory, skipping the calling test when it does not hold the derived
	 * matrix.
	 *
	 * @return the data directory
	 */
	public static File requireDirectory() {
		File directory = new File(SystemUtils.getProperty(DIRECTORY_PROPERTY, DEFAULT_DIRECTORY));
		Assume.assumeTrue(new File(directory, CellTypeConnectome.WEIGHTS_FILE).exists());
		return directory;
	}

	/**
	 * Every recomputed entry equals the loaded {@code W[post, pre]} exactly, and its names are
	 * those of its indices. The entries are signed synapse counts below 2^24 (the script asserts
	 * it), so FP32 holds them exactly.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void spotChecks() throws IOException {
		File directory = requireDirectory();

		try (CellTypeConnectome connectome = CellTypeConnectome.load(directory);
			 Reader reader = Files.newBufferedReader(new File(directory, SPOT_CHECKS_FILE).toPath(), StandardCharsets.UTF_8)) {
			int t = connectome.getTypeCount();
			PackedCollection weights = connectome.getWeights();
			JsonArray checks = JsonParser.parseReader(reader).getAsJsonArray();
			assertTrue(checks.size() >= 150);

			int zeros = 0;
			for (JsonElement element : checks) {
				JsonObject check = element.getAsJsonObject();
				int post = connectome.indexOf(check.get("post").getAsString());
				int pre = connectome.indexOf(check.get("pre").getAsString());
				assertEquals(post, check.get("post_index").getAsInt());
				assertEquals(pre, check.get("pre_index").getAsInt());

				double expected = check.get("value").getAsDouble();
				assertEquals(check.toString(), expected, weights.toDouble(post * t + pre), 0.0);
				if (expected == 0.0) zeros++;
			}

			log("spotChecks=" + checks.size() + " zeroEntries=" + zeros + " types=" + t);
			assertTrue(zeros > 0);
		}
	}

	/**
	 * {@code matmul(W, e_k)} evaluated on the device equals column {@code k} of the loaded matrix
	 * for the first, a middle and the last column. The matrix has more than 2^24 elements, so
	 * this checks that an operand of that size is addressed correctly, including its last
	 * column.
	 */
	@Test(timeout = 1800000)
	@TestDepth(2)
	public void deviceColumns() throws IOException {
		File directory = requireDirectory();

		try (CellTypeConnectome connectome = CellTypeConnectome.load(directory)) {
			int t = connectome.getTypeCount();
			PackedCollection weights = connectome.getWeights();
			assertTrue(weights.getMemLength() > (1 << 24));

			for (int k : new int[] { 0, t / 2, t - 1 }) {
				try (PackedCollection e = new PackedCollection(shape(t))) {
					e.clear();
					e.setMem(k, 1.0);
					PackedCollection column = matmul(cp(weights), cp(e)).evaluate();
					assertEquals(t, column.getMemLength());

					int nonzero = 0;
					for (int i = 0; i < t; i++) {
						double expected = weights.toDouble(i * t + k);
						assertEquals("W[" + i + ", " + k + "]", expected, column.toDouble(i), 0.0);
						if (expected != 0.0) nonzero++;
					}

					log("column=" + k + " nonzero=" + nonzero);
					column.destroy();
				}
			}
		}
	}

	/**
	 * The loaded per-type counts and annotations are consistent with the matrix: every type has
	 * at least one neuron, {@code MN9} is a motor type, and {@link CellTypeConnectome#inverseTypeSizes()}
	 * is {@code 1 / type_size}.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void typeTables() throws IOException {
		File directory = requireDirectory();

		try (CellTypeConnectome connectome = CellTypeConnectome.load(directory);
			 PackedCollection inverse = connectome.inverseTypeSizes().evaluate()) {
			int t = connectome.getTypeCount();
			PackedCollection sizes = connectome.getTypeSizes();
			for (int i = 0; i < t; i += 97) {
				assertTrue(sizes.toDouble(i) >= 1.0);
				assertEquals(1.0 / sizes.toDouble(i), inverse.toDouble(i), 1e-6);
			}

			int mn9 = connectome.indexOf("MN9");
			assertEquals("MN9", connectome.getTypeNames().get(mn9));
			assertTrue(connectome.getSuperclass(mn9).endsWith("motor"));
			assertTrue(connectome.getOutSynapses().toDouble(mn9) > 0.0);
		}
	}
}
