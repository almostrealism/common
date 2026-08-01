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

package org.almostrealism.collect.computations.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Profiled reproduction of the {@code subset -> pad} graph that produces wrong values under
 * the OpenCL backend while passing under Metal and native
 * ({@link PackedCollectionSubsetTests#subsetHalfPad2d} and the related sum tests). Running
 * this test under each backend (via {@code AR_HARDWARE_DRIVER}) writes an operation profile
 * containing the generated kernel source to {@code results/}, so the two backends' compiled
 * programs for the identical computation can be diffed directly.
 */
public class PadKernelSourceDiagTest extends TestSuiteBase {
	/** Directory where the diagnostic operation profiles are written. */
	private static final Path RESULTS_DIR = Paths.get("results");

	/**
	 * Builds the exact failing computation from {@code subsetHalfPad2d}, evaluates it under
	 * the active driver, reports the first row of pad-region and data-region values against
	 * their expected values, and saves the operation profile (including generated kernel
	 * source) named for the active driver.
	 */
	@Test(timeout = 120000)
	public void padSubsetProfile() throws IOException {
		Files.createDirectories(RESULTS_DIR);

		String driver = SystemUtils.getProperty("AR_HARDWARE_DRIVER", "default")
				.replaceAll("[^A-Za-z0-9]", "_");

		int seqLen = 16;
		int dim = 16;
		int halfDim = dim / 2;

		PackedCollection input = new PackedCollection(shape(seqLen, dim)).randFill();

		OperationProfileNode profile = new OperationProfileNode("padSubset_" + driver);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			CollectionProducer x = cp(input).subset(shape(seqLen, halfDim), 0, 0);
			CollectionProducer padded = pad(shape(seqLen, dim), x, 0, halfDim);

			PackedCollection result = padded.evaluate();

			int mismatches = 0;

			for (int s = 0; s < seqLen; s++) {
				for (int d = 0; d < dim; d++) {
					double expected = d >= halfDim ? input.valueAt(s, d % halfDim) : 0.0;
					if (Math.abs(result.valueAt(s, d) - expected) > 1e-5) mismatches++;
				}
			}

			log("driver=" + driver + " mismatches=" + mismatches +
					" of " + (seqLen * dim));

			for (int d = 0; d < dim; d++) {
				double expected = d >= halfDim ? input.valueAt(0, d % halfDim) : 0.0;
				log("row0 d=" + d + " actual=" + result.valueAt(0, d) +
						" expected=" + expected);
			}
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		String profilePath = RESULTS_DIR.resolve("padSubset_" + driver + ".xml").toString();
		profile.save(profilePath);
		log("Profile saved to: " + profilePath);
	}
}
