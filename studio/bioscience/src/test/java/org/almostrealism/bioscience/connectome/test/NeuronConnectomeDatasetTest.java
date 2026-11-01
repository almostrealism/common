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
 * environment variable, by default under {@value NeuronConnectomeChecks#DERIVED}. The datasets
 * are too large for the repository and for every runner, so these tests are long-running and
 * excluded from the pipeline profile: they run when asked for, and then a missing dataset fails
 * the test on every host ({@link NeuronConnectomeChecks#requireConnectome}). The same
 * checks run in the pipeline on the committed FlyWire sugar circuit, a real connectome cut from
 * the 630 snapshot ({@link NeuronConnectomeTest}).</p>
 */
public class NeuronConnectomeDatasetTest extends NeuronConnectomeChecks {
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
