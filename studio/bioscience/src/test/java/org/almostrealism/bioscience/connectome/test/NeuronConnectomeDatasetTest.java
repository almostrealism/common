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
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * {@link NeuronConnectome} on the full datasets converted by
 * {@code studio/bioscience/scripts/connectome_csr.py}: MaleCNS (164,506 typed neurons,
 * 25,135,616 edges, more entries than FP32 holds exactly) and the FlyWire snapshots 630 and 783
 * distributed with Shiu et al. (2024).
 *
 * <p>The converted data lives outside the repository. Each directory is a system property or
 * environment variable, by default under {@value #DERIVED}. The datasets are too large for the
 * repository and for every runner, so these tests are long-running and excluded from the
 * pipeline profile: they run when asked for, and then a missing dataset fails the test. The same
 * checks run in the pipeline on the committed FlyWire sugar circuit, a real connectome cut from
 * the 630 snapshot ({@link NeuronConnectomeTest}).</p>
 */
public class NeuronConnectomeDatasetTest extends NeuronConnectomeChecks {
	/** The directory under which converted datasets live by default. */
	public static final String DERIVED = "/Users/Shared/models-derived";

	/** The property or environment variable naming the converted MaleCNS directory. */
	public static final String MALE_CNS_PROPERTY = "AR_MALE_CNS_CSR";

	/** The property or environment variable naming the converted FlyWire 630 directory. */
	public static final String FLYWIRE_630_PROPERTY = "AR_FLYWIRE_630_CSR";

	/** The property or environment variable naming the converted FlyWire 783 directory. */
	public static final String FLYWIRE_783_PROPERTY = "AR_FLYWIRE_783_CSR";

	/** Returns the converted MaleCNS directory, failing the test when it is absent. */
	public static File maleCns() {
		return requireDataset(MALE_CNS_PROPERTY, DERIVED + "/male-cns/csr", NeuronConnectome.TENSORS_FILE);
	}

	/** Returns the converted FlyWire 630 directory, failing the test when it is absent. */
	public static File flywire630() {
		return requireDataset(FLYWIRE_630_PROPERTY, DERIVED + "/flywire/csr-630", NeuronConnectome.TENSORS_FILE);
	}

	/** Returns the converted FlyWire 783 directory, failing the test when it is absent. */
	public static File flywire783() {
		return requireDataset(FLYWIRE_783_PROPERTY, DERIVED + "/flywire/csr-783", NeuronConnectome.TENSORS_FILE);
	}

	/** MaleCNS loads with the totals the script recorded. */
	@Test(timeout = 1200000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void maleCnsTotals() throws IOException {
		assertTotals(maleCns());
	}

	/**
	 * The MaleCNS spot checks, including both MN9 neurons, agree with the script's
	 * recomputation from the source table, and both MN9 neurons are annotated as motor neurons.
	 */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void maleCnsSpotChecks() throws IOException {
		File directory = maleCns();
		assertSpotChecks(directory);

		try (NeuronConnectome connectome = NeuronConnectome.load(directory)) {
			int[] mn9 = connectome.indicesOfType("MN9"::equals);
			Assert.assertArrayEquals(connectome.indicesOf(10331, 16949), mn9);
			for (int index : mn9) {
				assertEquals("cb_motor", connectome.getSuperclass(index));
			}
		}
	}

	/** FlyWire 630, the snapshot of the paper, loads with the totals the script recorded. */
	@Test(timeout = 1200000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void flywire630Totals() throws IOException {
		assertTotals(flywire630());
	}

	/** The FlyWire 630 spot checks, including MN9 and a sugar GRN, agree with the source table. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void flywire630SpotChecks() throws IOException {
		assertSpotChecks(flywire630());
	}

	/** The FlyWire 783 spot checks agree with the source table. */
	@Test(timeout = 2400000)
	@TestDepth(2)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void flywire783SpotChecks() throws IOException {
		File directory = flywire783();
		assertTotals(directory);
		assertSpotChecks(directory);
	}
}
