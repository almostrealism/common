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

package org.almostrealism.persist.assets.test;

import io.almostrealism.code.Precision;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.protobuf.Collections;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests for the protobuf-backed memory provider: collections produced by
 * {@link CollectionEncoder#decodeDeferred} read from the message on the host,
 * migrate to the compute device at first kernel use, and reject writes while
 * still message-backed.
 */
public class CollectionDataMemoryProviderTest extends TestSuiteBase {

	/** Number of elements in the test tensors. */
	private static final int SIZE = 96;

	/** Produces a collection whose values are a fixed function of the index. */
	private PackedCollection testValues() {
		PackedCollection c = new PackedCollection(shape(SIZE));
		integers(0, SIZE).multiply(0.25).add(1.0)
				.into(c.traverseEach()).evaluate();
		return c;
	}

	/**
	 * Host reads from a deferred collection must match the encoded source,
	 * at both encoded precisions, without the data leaving the message.
	 */
	@Test(timeout = 60000)
	public void deferredHostReadsMatchSource() {
		PackedCollection source = testValues();

		for (Precision precision : new Precision[] { Precision.FP64, Precision.FP32 }) {
			Collections.CollectionData data = CollectionEncoder.encode(source, precision);
			PackedCollection deferred = CollectionEncoder.decodeDeferred(data);

			Assert.assertEquals("PROTOBUF",
					deferred.getRootDelegate().getMem().getProvider().getName());
			Assert.assertEquals(SIZE, deferred.getShape().getTotalSize());

			double tolerance = precision == Precision.FP64 ? 0.0 : 1e-6;
			for (int i = 0; i < SIZE; i++) {
				Assert.assertEquals("element " + i + " at " + precision,
						source.toDouble(i), deferred.toDouble(i), tolerance);
			}
		}
	}

	/**
	 * A kernel consuming a small deferred collection computes correctly while
	 * the collection stays message-backed: below the aggregation threshold the
	 * framework copies the values into the dispatch aggregate (reading through
	 * the provider) instead of migrating the root, so host-mostly data never
	 * permanently occupies device memory.
	 */
	@Test(timeout = 120000)
	public void smallDeferredComputesWithoutMigrating() {
		PackedCollection source = testValues();
		PackedCollection deferred = CollectionEncoder.decodeDeferred(
				CollectionEncoder.encode(source, Precision.FP64));

		Assert.assertEquals("PROTOBUF",
				deferred.getRootDelegate().getMem().getProvider().getName());

		PackedCollection doubled = cp(deferred).multiply(2.0).evaluate();
		for (int i = 0; i < SIZE; i++) {
			Assert.assertEquals("element " + i,
					2.0 * source.toDouble(i), doubled.toDouble(i), 1e-9);
		}

		Assert.assertEquals("small arguments aggregate per dispatch rather than migrating",
				"PROTOBUF", deferred.getRootDelegate().getMem().getProvider().getName());
	}

	/**
	 * A kernel consuming a deferred collection larger than the aggregation
	 * threshold (1,048,576 elements) triggers migration of the root
	 * reservation to the compute device, computes correctly, and leaves the
	 * collection reading from device memory afterward.
	 */
	@Test(timeout = 120000)
	public void largeDeferredMigratesAtFirstKernelUse() {
		int size = 1100000;
		PackedCollection source = new PackedCollection(shape(size));
		integers(0, size).multiply(0.001).add(1.0)
				.into(source.traverseEach()).evaluate();

		PackedCollection deferred = CollectionEncoder.decodeDeferred(
				CollectionEncoder.encode(source, Precision.FP64));
		Assert.assertEquals("PROTOBUF",
				deferred.getRootDelegate().getMem().getProvider().getName());

		double[] expected = source.toArray();
		double[] doubled = cp(deferred).multiply(2.0).evaluate().toArray();
		for (int i = 0; i < size; i++) {
			Assert.assertEquals("element " + i, 2.0 * expected[i], doubled[i], 1e-9);
		}

		Assert.assertNotEquals("kernel use should have migrated the root reservation",
				"PROTOBUF", deferred.getRootDelegate().getMem().getProvider().getName());

		double[] after = deferred.toArray();
		for (int i = 0; i < size; i++) {
			Assert.assertEquals("element " + i + " after migration",
					expected[i], after[i], 1e-9);
		}
	}

	/**
	 * Message-backed memory is a read-only source: writes are rejected rather
	 * than silently lost, since migration is one-way.
	 */
	@Test(timeout = 60000)
	public void deferredRejectsWritesBeforeMigration() {
		PackedCollection deferred = CollectionEncoder.decodeDeferred(
				CollectionEncoder.encode(testValues(), Precision.FP64));

		try {
			deferred.setMem(0, 1.0);
			Assert.fail("Write into message-backed memory should be rejected");
		} catch (UnsupportedOperationException e) {
			// Expected: the provider is a read-only source
		}
	}

	/**
	 * A {@link StateDictionary} saved to disk and reloaded with deferred
	 * weights enabled serves identical values, and its weights work as
	 * kernel arguments.
	 */
	@Test(timeout = 120000)
	public void stateDictionaryRoundTripWithDeferredWeights() throws IOException {
		Map<String, PackedCollection> weights = new HashMap<>();
		weights.put("layer.alpha", testValues());
		weights.put("layer.beta", testValues());

		Path dir = Files.createTempDirectory("deferred-weights-test");
		new StateDictionary(weights).save(dir.resolve("weights.pb"), Precision.FP64);

		boolean previous = StateDictionary.enableDeferredWeights;
		StateDictionary.enableDeferredWeights = true;

		try {
			StateDictionary loaded = new StateDictionary(dir.toString());
			Assert.assertEquals(2, loaded.size());

			for (String key : weights.keySet()) {
				PackedCollection expected = weights.get(key);
				PackedCollection actual = loaded.get(key);
				Assert.assertEquals("PROTOBUF",
						actual.getRootDelegate().getMem().getProvider().getName());

				for (int i = 0; i < SIZE; i++) {
					Assert.assertEquals(key + " element " + i,
							expected.toDouble(i), actual.toDouble(i), 1e-9);
				}
			}

			PackedCollection shifted = cp(loaded.get("layer.alpha")).add(1.0).evaluate();
			for (int i = 0; i < SIZE; i++) {
				Assert.assertEquals("shifted element " + i,
						weights.get("layer.alpha").toDouble(i) + 1.0,
						shifted.toDouble(i), 1e-9);
			}

			loaded.destroy();
		} finally {
			StateDictionary.enableDeferredWeights = previous;
		}
	}
}
