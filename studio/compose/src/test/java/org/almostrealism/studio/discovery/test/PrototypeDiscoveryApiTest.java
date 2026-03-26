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

package org.almostrealism.studio.discovery.test;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.studio.discovery.PrototypeDiscovery;
import org.almostrealism.audio.similarity.PrototypeIndexData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Tests for the {@link PrototypeDiscovery#discoverPrototypes} static API,
 * verifying end-to-end prototype discovery with a pre-populated library.
 */
public class PrototypeDiscoveryApiTest extends TestSuiteBase {

	private File tempDir;
	private AudioLibrary library;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("proto-discovery-api-test").toFile();
		library = new AudioLibrary(tempDir, 44100);
	}

	@After
	public void tearDown() {
		if (library != null) {
			library.stop();
		}
		if (tempDir != null) {
			tempDir.delete();
		}
	}

	/**
	 * Verifies that {@link PrototypeDiscovery#discoverPrototypes} returns
	 * prototypes when given a library with two clusters of pre-computed
	 * similarities. Cluster A (a1, a2, a3) has high internal similarity;
	 * cluster B (b1, b2) has high internal similarity; cross-cluster
	 * similarity is low. The algorithm should detect two communities.
	 */
	@Test(timeout = 60000)
	public void discoverPrototypesReturnsCommunities()
			throws PrototypeDiscovery.PrototypeDiscoveryException {
		// Build cluster A: a1, a2, a3 with high mutual similarity
		WaveDetails a1 = createDetails("a1");
		WaveDetails a2 = createDetails("a2");
		WaveDetails a3 = createDetails("a3");
		setSimilarity(a1, a2, 0.9);
		setSimilarity(a1, a3, 0.85);
		setSimilarity(a2, a3, 0.88);

		// Build cluster B: b1, b2 with high mutual similarity
		WaveDetails b1 = createDetails("b1");
		WaveDetails b2 = createDetails("b2");
		setSimilarity(b1, b2, 0.92);

		// Cross-cluster similarity is low
		setSimilarity(a1, b1, 0.1);
		setSimilarity(a1, b2, 0.12);
		setSimilarity(a2, b1, 0.11);
		setSimilarity(a2, b2, 0.09);
		setSimilarity(a3, b1, 0.08);
		setSimilarity(a3, b2, 0.13);

		library.include(a1);
		library.include(a2);
		library.include(a3);
		library.include(b1);
		library.include(b2);

		List<PrototypeDiscovery.PrototypeResult> results =
				PrototypeDiscovery.discoverPrototypes(library, 5, null);

		Assert.assertNotNull("Results should not be null", results);
		Assert.assertTrue("Should find at least 1 community", results.size() >= 1);
		Assert.assertTrue("Should find at most 5 communities", results.size() <= 5);

		// Verify result structure
		for (PrototypeDiscovery.PrototypeResult result : results) {
			Assert.assertNotNull("Prototype identifier must not be null",
					result.identifier());
			Assert.assertTrue("Community size must be positive",
					result.communitySize() > 0);
			Assert.assertFalse("Member list must not be empty",
					result.memberIdentifiers().isEmpty());
			Assert.assertTrue("Prototype must be in its own community members",
					result.memberIdentifiers().contains(result.identifier()));
		}

		// Verify sorting: largest community first
		for (int i = 1; i < results.size(); i++) {
			Assert.assertTrue("Results should be sorted by community size descending",
					results.get(i - 1).communitySize() >= results.get(i).communitySize());
		}
	}

	/**
	 * Verifies that discoverPrototypes respects the maxPrototypes limit.
	 */
	@Test(timeout = 60000)
	public void discoverPrototypesRespectsMaxLimit()
			throws PrototypeDiscovery.PrototypeDiscoveryException {
		// Create enough entries to form multiple clusters
		List<WaveDetails> details = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			details.add(createDetails("node-" + i));
		}

		// Connect all pairs with moderate similarity (single community)
		for (int i = 0; i < details.size(); i++) {
			for (int j = i + 1; j < details.size(); j++) {
				setSimilarity(details.get(i), details.get(j), 0.5 + 0.1 * (i % 3));
			}
		}

		for (WaveDetails d : details) {
			library.include(d);
		}

		List<PrototypeDiscovery.PrototypeResult> results =
				PrototypeDiscovery.discoverPrototypes(library, 1, null);

		Assert.assertTrue("Should return at most 1 prototype",
				results.size() <= 1);
	}

	/**
	 * Verifies that discoverPrototypes throws PrototypeDiscoveryException
	 * when the library has no samples.
	 */
	@Test(timeout = 30000, expected = PrototypeDiscovery.PrototypeDiscoveryException.class)
	public void discoverPrototypesThrowsForEmptyLibrary()
			throws PrototypeDiscovery.PrototypeDiscoveryException {
		PrototypeDiscovery.discoverPrototypes(library, 5, null);
	}

	/**
	 * Verifies that discoverPrototypes invokes the status callback
	 * with progress messages.
	 */
	@Test(timeout = 60000)
	public void discoverPrototypesReportsProgress()
			throws PrototypeDiscovery.PrototypeDiscoveryException {
		WaveDetails d1 = createDetails("p1");
		WaveDetails d2 = createDetails("p2");
		setSimilarity(d1, d2, 0.7);

		library.include(d1);
		library.include(d2);

		List<String> messages = new ArrayList<>();
		List<PrototypeDiscovery.PrototypeResult> results =
				PrototypeDiscovery.discoverPrototypes(library, 5, messages::add);

		Assert.assertNotNull(results);
		Assert.assertFalse("Should have received status messages",
				messages.isEmpty());
	}

	/**
	 * Verifies that the end-to-end pipeline from discoverPrototypes through
	 * buildIndex produces a valid PrototypeIndexData.
	 */
	@Test(timeout = 60000)
	public void discoverAndBuildIndexEndToEnd()
			throws PrototypeDiscovery.PrototypeDiscoveryException {
		WaveDetails d1 = createDetails("e2e-1");
		WaveDetails d2 = createDetails("e2e-2");
		WaveDetails d3 = createDetails("e2e-3");
		setSimilarity(d1, d2, 0.8);
		setSimilarity(d1, d3, 0.75);
		setSimilarity(d2, d3, 0.82);

		library.include(d1);
		library.include(d2);
		library.include(d3);

		List<PrototypeDiscovery.PrototypeResult> results =
				PrototypeDiscovery.discoverPrototypes(library, 10, null);

		PrototypeIndexData index = PrototypeDiscovery.buildIndex(results);

		Assert.assertNotNull("Index must not be null", index);
		Assert.assertFalse("Index should have communities",
				index.communities().isEmpty());
		Assert.assertTrue("computedAt should be recent",
				index.computedAt() > System.currentTimeMillis() - 60000);
		Assert.assertTrue("Total indexed members should be positive",
				index.totalIndexedMembers() > 0);
	}

	// ── Helpers ─────────────────────────────────────────────────────────

	private WaveDetails createDetails(String identifier) {
		WaveDetails details = new WaveDetails(identifier, 44100);
		details.setFreqData(new PackedCollection(1));
		details.setFeatureData(new PackedCollection(1));
		details.setSimilarities(new HashMap<>());
		return details;
	}

	private void setSimilarity(WaveDetails a, WaveDetails b, double value) {
		a.getSimilarities().put(b.getIdentifier(), value);
		b.getSimilarities().put(a.getIdentifier(), value);
	}
}
