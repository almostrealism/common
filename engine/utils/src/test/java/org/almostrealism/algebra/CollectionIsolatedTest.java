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

package org.almostrealism.algebra;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link CollectionFeatures#isolated(Producer)}, for producers that are
 * {@link Process processes} and for producers that are not.
 */
public class CollectionIsolatedTest extends TestSuiteBase {

	/**
	 * A shaped producer of a fixed collection that is not a {@link Process}, standing in for
	 * any {@link Producer} implementation outside the process tree.
	 */
	private static class FixedProducer implements Producer<PackedCollection>, Shape<Producer<PackedCollection>> {
		/** The collection every evaluation returns. */
		private final PackedCollection value;

		/**
		 * Creates a producer of the given collection.
		 *
		 * @param value the collection to produce
		 */
		FixedProducer(PackedCollection value) {
			this.value = value;
		}

		@Override
		public Evaluable<PackedCollection> get() {
			return args -> value;
		}

		@Override
		public TraversalPolicy getShape() {
			return value.getShape();
		}

		@Override
		public Producer<PackedCollection> reshape(TraversalPolicy shape) {
			return new FixedProducer(value.reshape(shape));
		}

		@Override
		public Producer<PackedCollection> traverse(int axis) {
			return new FixedProducer(value.traverse(axis));
		}
	}

	/**
	 * Isolating a producer that is not a {@link Process} returns a collection producer of the
	 * same values instead of failing to cast the generic {@link Process#isolated} wrapper.
	 */
	@Test(timeout = 60000)
	public void isolatedNonProcessProducer() {
		PackedCollection value = pack(1.0, 2.0, 3.0, 4.0);
		FixedProducer producer = new FixedProducer(value);
		Assert.assertFalse(producer instanceof Process);

		CollectionProducer isolated = isolated(producer);
		Assert.assertEquals(4, shape(isolated).getTotalSize());

		PackedCollection result = isolated.multiply(c(2.0)).evaluate();
		for (int i = 0; i < 4; i++) {
			Assert.assertEquals(2.0 * (i + 1), result.toDouble(i), 1e-6);
		}
	}

	/**
	 * Isolating a computation preserves its shape and values when the result is read by
	 * several consumers.
	 */
	@Test(timeout = 60000)
	public void isolatedComputation() {
		PackedCollection a = pack(1.0, 2.0, 3.0);
		PackedCollection b = pack(10.0, 20.0, 30.0);

		CollectionProducer sum = isolated(cp(a).add(cp(b)));
		Assert.assertEquals(3, shape(sum).getTotalSize());

		PackedCollection result = sum.multiply(sum).add(sum).evaluate();
		for (int i = 0; i < 3; i++) {
			double s = a.toDouble(i) + b.toDouble(i);
			Assert.assertEquals(s * s + s, result.toDouble(i), 1e-3);
		}
	}
}
