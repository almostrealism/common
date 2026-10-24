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

package org.almostrealism.collect.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the shared reshape total-size invariant enforced by
 * {@link TraversalPolicy#requireReshape(TraversalPolicy)} and each of the call sites that
 * delegate to it: {@link org.almostrealism.model.Block#reshape(TraversalPolicy)},
 * {@link LayerFeatures#reshape(TraversalPolicy, TraversalPolicy)}, and the
 * {@link ReshapeProducer} constructor. A reshape preserves the number of elements, so a
 * target whose total size differs from the source is rejected with an
 * {@link IllegalArgumentException} naming both shapes and their differing total sizes; a
 * target with a matching total size (even with different dimensions) is accepted.
 */
public class ReshapeValidationTests extends TestSuiteBase implements LayerFeatures {

	/**
	 * Asserts that {@code operation} throws an {@link IllegalArgumentException} whose message
	 * reports a reshape between shapes of differing total size.
	 */
	private static void assertReshapeRejected(long expectedSize, long actualSize, Runnable operation) {
		try {
			operation.run();
		} catch (IllegalArgumentException e) {
			Assert.assertTrue("Message should describe the reshape: " + e.getMessage(),
					e.getMessage().startsWith("Cannot reshape "));
			Assert.assertTrue("Message should report both total sizes: " + e.getMessage(),
					e.getMessage().contains(expectedSize + " vs " + actualSize));
			return;
		}

		Assert.fail("Expected IllegalArgumentException for a reshape from " + expectedSize +
				" to " + actualSize + " elements");
	}

	/**
	 * {@link TraversalPolicy#requireReshape(TraversalPolicy)} returns the target shape when it
	 * has the same total size as the source, regardless of how the dimensions are arranged.
	 */
	@Test(timeout = 10000)
	public void requireReshapeReturnsTargetWhenSizesMatch() {
		TraversalPolicy target = shape(2, 12);
		Assert.assertSame(target, shape(4, 6).requireReshape(target));
		Assert.assertSame(target, shape(24).requireReshape(target));
	}

	/**
	 * {@link TraversalPolicy#requireReshape(TraversalPolicy)} rejects a target whose total size
	 * differs, reporting both the source and target total sizes.
	 */
	@Test(timeout = 10000)
	public void requireReshapeRejectsSizeMismatch() {
		assertReshapeRejected(24, 30, () -> shape(4, 6).requireReshape(shape(5, 6)));
	}

	/**
	 * {@link org.almostrealism.model.Block#reshape(TraversalPolicy)} rejects a target whose
	 * total size differs from the block's output shape.
	 */
	@Test(timeout = 10000)
	public void blockReshapeRejectsSizeMismatch() {
		assertReshapeRejected(24, 30,
				() -> passThrough(shape(4, 6)).reshape(shape(5, 6)));
	}

	/**
	 * {@link LayerFeatures#reshape(TraversalPolicy, TraversalPolicy)} accepts matching total
	 * sizes and rejects differing ones.
	 */
	@Test(timeout = 10000)
	public void layerReshapeValidatesTotalSize() {
		Assert.assertNotNull(reshape(shape(4, 6), shape(24)));
		assertReshapeRejected(24, 30,
				() -> reshape(shape(4, 6), shape(5, 6)));
	}

	/**
	 * The {@link ReshapeProducer} constructor accepts a target with a matching total size and
	 * rejects one whose total size differs from the input producer's shape.
	 */
	@Test(timeout = 10000)
	public void reshapeProducerValidatesTotalSize() {
		Producer<PackedCollection> input = cp(new PackedCollection(shape(4, 6)));
		Assert.assertNotNull(new ReshapeProducer(shape(24), input));
		assertReshapeRejected(24, 30,
				() -> new ReshapeProducer(shape(5, 6), input));
	}
}
