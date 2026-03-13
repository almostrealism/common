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

package org.almostrealism.audio.discovery.test;

import org.almostrealism.audio.discovery.PrototypeDiscovery;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;

/**
 * Manual diagnostic test for running {@link PrototypeDiscovery} against
 * real data to diagnose cache miss / loadSingleDetail behavior.
 *
 * <p>This test requires local data files that are not available in CI.
 * It is marked {@link TestDepth @TestDepth(10)} to prevent automatic
 * execution. To run manually, set {@code AR_TEST_DEPTH=10} or higher.</p>
 */
public class PrototypeDiscoveryTest extends TestSuiteBase {

	private static final String PROTOBUF_PREFIX = "/Users/michael/Projects/AlmostRealism/library";
	private static final String SAMPLES_ROOT = "/Users/michael/Music/Samples";

	@Test @TestDepth(10)
	public void runDiscovery() throws Exception {
		File protobufFile = new File(PROTOBUF_PREFIX + "_0.bin");
		if (!protobufFile.exists()) {
			System.out.println("SKIP: Protobuf file not found: " + protobufFile);
			return;
		}

		PrototypeDiscovery discovery = new PrototypeDiscovery(
				PROTOBUF_PREFIX,
				new File(SAMPLES_ROOT).isDirectory() ? SAMPLES_ROOT : null,
				5, false);
		discovery.run();
	}
}
