/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.collect.RepeatTraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.ExplicitIndexTraversalOrdering;
import org.almostrealism.collect.computations.CollectionProvider;
import org.almostrealism.collect.IndexMaskTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for collection traversal ordering implementations.
 */
public class CollectionOrderingTests extends TestSuiteBase {

	/**
	 * Tests repeat ordering traversal.
	 */
	@Test(timeout = 10000)
	@TestProperties(knownIssue = true)
	public void repeatOrdering() {

		PackedCollection root = pack(2.0, 3.0, 1.0);
		PackedCollection repeated = new PackedCollection(shape(4, 3), 1,
				root, 0, new RepeatTraversalOrdering(3));
		repeated.print();

		assertEquals(2.0, repeated.valueAt(0, 0));
		assertEquals(3.0, repeated.valueAt(0, 1));
		assertEquals(1.0, repeated.valueAt(0, 2));
		assertEquals(2.0, repeated.valueAt(1, 0));
		assertEquals(3.0, repeated.valueAt(1, 1));
		assertEquals(1.0, repeated.valueAt(1, 2));
	}

	/**
	 * Tests repeat ordering with product operation.
	 */
	@Test(timeout = 10000)
	public void repeatOrderingProduct() {
		PackedCollection root = pack(2.0, 3.0, 1.0);
		PackedCollection repeated = new PackedCollection(shape(4, 3), 1,
				root, 0, new RepeatTraversalOrdering(3));

		verboseLog(() -> {
			PackedCollection product = c(2).multiply(cp(repeated)).evaluate();
			product.print();

			assertEquals(4.0, product.valueAt(0, 0));
			assertEquals(6.0, product.valueAt(0, 1));
			assertEquals(2.0, product.valueAt(0, 2));
			assertEquals(4.0, product.valueAt(3, 0));
			assertEquals(6.0, product.valueAt(3, 1));
			assertEquals(2.0, product.valueAt(3, 2));
		});
	}

	/**
	 * Tests compact ordering traversal.
	 */
	@Test(timeout = 10000)
	@TestProperties(knownIssue = true)
	public void compactOrdering() {

		PackedCollection values = pack(2.0, 3.0);

		ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(pack(0, -1, -1, 1));
		PackedCollection compact = new PackedCollection(shape(2, 2), 1, values, 0, order);

		compact.print();

		assertEquals(2.0, compact.valueAt(0, 0));
		assertEquals(0.0, compact.valueAt(0, 1));
		assertEquals(0.0, compact.valueAt(1, 0));
		assertEquals(3.0, compact.valueAt(1, 1));
	}

	/**
	 * Tests mask ordering traversal.
	 */
	@Test(timeout = 10000)
	@TestProperties(knownIssue = true)
	public void maskOrdering() {

		PackedCollection values = pack(2.0, 3.0);
		PackedCollection indices = pack(0, 3);

		IndexMaskTraversalOrdering order = new IndexMaskTraversalOrdering(indices);
		PackedCollection compact = new PackedCollection(shape(2, 2), 1, values, 0, order);

		compact.print();

		assertEquals(2.0, compact.valueAt(0, 0));
		assertEquals(0.0, compact.valueAt(0, 1));
		assertEquals(0.0, compact.valueAt(1, 0));
		assertEquals(3.0, compact.valueAt(1, 1));
	}

	/**
	 * Verifies that a delegated view with a non-idempotent {@link ExplicitIndexTraversalOrdering}
	 * (a fixed-point-free permutation) round-trips every element correctly through
	 * {@link PackedCollection#toDouble(int)}.
	 *
	 * <p>The ordering must be applied exactly once per logical index. Applying it twice
	 * (mapping the already-physical index through the ordering a second time) silently
	 * produces the wrong element instead of the value the permutation actually designates.</p>
	 */
	@Test(timeout = 10000)
	public void explicitIndexOrderingAppliedOnce() {
		PackedCollection values = pack(10.0, 20.0, 30.0, 40.0);
		PackedCollection indices = pack(2, 0, 3, 1);

		ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(indices);
		PackedCollection permuted = new PackedCollection(shape(4), 1, values, 0, order);

		assertEquals(30.0, permuted.toDouble(0));
		assertEquals(10.0, permuted.toDouble(1));
		assertEquals(40.0, permuted.toDouble(2));
		assertEquals(20.0, permuted.toDouble(3));
	}

	/**
	 * A provider over a collection whose shape carries an explicit
	 * {@link ExplicitIndexTraversalOrdering} is refused by
	 * {@link org.almostrealism.collect.computations.CollectionProvider#into(Object)} when the
	 * destination does not carry the same ordering, rather than flat-copying the backing memory
	 * and silently dropping the ordering.
	 *
	 * <p>The ordering makes the shape irregular while leaving its dimensions, dimension order, rates
	 * and axis equal to a plain destination of the same size, so
	 * {@link io.almostrealism.collect.TraversalPolicy#equals(Object)} — which does not compare the
	 * traversal ordering — reports the two policies equal. A compatibility check that trusted that
	 * equality would let the flat copy proceed and write backing-memory order; the ordering must be
	 * compared as well.</p>
	 */
	@Test(timeout = 10000)
	public void orderedSourceIntoPlainDestinationIsRefused() {
		try (PackedCollection values = pack(10.0, 20.0, 30.0, 40.0);
				PackedCollection indices = pack(2, 0, 3, 1);
				PackedCollection destination = new PackedCollection(shape(4))) {
			ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(indices);
			PackedCollection ordered = new PackedCollection(shape(4), 0, values, 0, order);

			assertFalse("the ordered shape should be irregular", ordered.getShape().isRegular());
			assertTrue("the destination should be regular", destination.getShape().isRegular());
			assertTrue("equals should ignore the ordering, reporting the policies equal",
					ordered.getShape().equals(destination.getShape()));

			try {
				cp(ordered).get().into(destination).evaluate();
				throw new AssertionError("an ordered source was copied into a plain destination");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("views other memory"));
			}
		}
	}

	/**
	 * A provider over a collection whose outer {@link io.almostrealism.collect.TraversalPolicy} is
	 * regular, but which inherits a {@link ExplicitIndexTraversalOrdering} from its delegate, is
	 * refused by
	 * {@link org.almostrealism.collect.computations.CollectionProvider#into(Object)} just as an
	 * explicitly ordered shape is, rather than flat-copying the backing memory and silently dropping
	 * the ordering.
	 *
	 * <p>Here {@link org.almostrealism.collect.PackedCollection#getShape()} reports a regular shape —
	 * so {@link io.almostrealism.collect.TraversalPolicy#isRegular()} is {@code true} and the shape
	 * alone would admit the flat copy — while
	 * {@link org.almostrealism.hardware.MemoryData#getMemOrdering()} composes the delegate's ordering,
	 * so logical reads still apply it. The guard must inspect the memory ordering, not only the
	 * shape.</p>
	 */
	@Test(timeout = 10000)
	public void inheritedOrderingIntoPlainDestinationIsRefused() {
		try (PackedCollection values = pack(10.0, 20.0, 30.0, 40.0);
				PackedCollection indices = pack(2, 0, 3, 1);
				PackedCollection destination = new PackedCollection(shape(4))) {
			ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(indices);
			PackedCollection ordered = new PackedCollection(shape(4), 0, values, 0, order);
			PackedCollection view = new PackedCollection(shape(4), 0, ordered, 0);

			assertTrue("the view's outer shape should be regular", view.getShape().isRegular());
			assertNotNull("the view should inherit the delegate's memory ordering", view.getMemOrdering());
			assertTrue("the destination should be regular", destination.getShape().isRegular());

			try {
				cp(view).get().into(destination).evaluate();
				throw new AssertionError("a view inheriting a delegate ordering was copied into a plain destination");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("views other memory"));
			}
		}
	}

	/**
	 * When the source and destination carry the identical mapping, the flat copy is accepted, and it
	 * transfers one element per input position rather than per logical output position.
	 *
	 * <p>A rated shape such as {@code new TraversalPolicy(3).repeat(0, 4)} reports a total size of 12
	 * logical elements while its backing memory holds only 3 — its
	 * {@link TraversalPolicy#getTotalInputSize() total input size}, which is what
	 * {@link PackedCollection#getMemLength()} allocates. Because the destination shares the mapping, the
	 * copy is permitted; sizing it by the logical total size would read and write 12 elements through
	 * 3-element allocations, overrunning them. The copy must be sized by the input size, so this asserts
	 * the three backing values arrive intact.</p>
	 */
	@Test(timeout = 10000)
	public void sharedRatedMappingCopiesInputElements() {
		TraversalPolicy rated = new TraversalPolicy(3).repeat(0, 4);

		try (PackedCollection root = pack(2.0, 3.0, 1.0);
				PackedCollection destination = new PackedCollection(rated)) {
			PackedCollection source = new PackedCollection(rated, rated.getTraversalAxis(), root, 0);

			assertFalse("the rated shape should be irregular", source.getShape().isRegular());
			assertEquals("the rated shape reports 12 logical elements", 12, source.getShape().getTotalSize());
			assertEquals("the rated shape backs only 3 input elements", 3, source.getMemLength());
			assertTrue("equals should report the identical mappings equal",
					source.getShape().equals(destination.getShape()));

			new CollectionProvider<>(source).into(destination).evaluate();

			double[] copied = destination.toArray(0, 3);
			assertEquals(2.0, copied[0]);
			assertEquals(3.0, copied[1]);
			assertEquals(1.0, copied[2]);
		}
	}

	/**
	 * A provider over a collection carrying an explicit {@link ExplicitIndexTraversalOrdering} is
	 * refused by {@link org.almostrealism.collect.computations.CollectionProvider#into(Object)} even
	 * when the destination carries the identical ordering, rather than flat-copying the backing
	 * memory.
	 *
	 * <p>The "identical mapping" escape hatch is safe only for a rate or dimension mapping (as
	 * {@link #sharedRatedMappingCopiesInputElements()} covers), which reads a dense run of backing
	 * memory. A {@link io.almostrealism.collect.TraversalOrdering} can instead map a logical index
	 * onto a sparse backing index, so a flat transfer of the leading input elements would not
	 * reproduce it even when both sides share the ordering — and a compact ordering can make the
	 * transferred length overrun the delegate. Such a source must therefore be refused regardless of
	 * the destination's ordering; the reordering belongs in a computation.</p>
	 */
	@Test(timeout = 10000)
	public void orderedSourceIntoIdenticallyOrderedDestinationIsRefused() {
		try (PackedCollection values = pack(10.0, 20.0, 30.0, 40.0);
				PackedCollection indices = pack(2, 0, 3, 1);
				PackedCollection destinationValues = new PackedCollection(shape(4))) {
			ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(indices);
			PackedCollection ordered = new PackedCollection(shape(4), 0, values, 0, order);
			PackedCollection destination = new PackedCollection(shape(4), 0, destinationValues, 0, order);

			assertFalse("the ordered source should be irregular", ordered.getShape().isRegular());
			assertTrue("the two shapes should compare equal",
					ordered.getShape().equals(destination.getShape()));
			assertNotNull("the source should carry a memory ordering", ordered.getMemOrdering());
			assertEquals(ordered.getMemOrdering(), destination.getMemOrdering());

			try {
				new CollectionProvider<>(ordered).into(destination).evaluate();
				throw new AssertionError("an ordered source was flat-copied into an identically ordered destination");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("views other memory"));
			}
		}
	}

	/**
	 * A regular provider is refused by
	 * {@link org.almostrealism.collect.computations.CollectionProvider#into(Object)} when the
	 * destination carries an explicit {@link ExplicitIndexTraversalOrdering}.
	 *
	 * <p>The flat copy writes the destination's backing memory in order, so a destination ordered
	 * {@code [2, 0, 3, 1]} would read the copied values back as
	 * {@code [source[2], source[0], source[3], source[1]]} rather than as the source. The destination's
	 * ordering is inspected as well as the source's, and the backing memory is left untouched.</p>
	 */
	@Test(timeout = 10000)
	public void plainSourceIntoOrderedDestinationIsRefused() {
		try (PackedCollection source = pack(10.0, 20.0, 30.0, 40.0);
				PackedCollection indices = pack(2, 0, 3, 1);
				PackedCollection destinationValues = new PackedCollection(shape(4))) {
			ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(indices);
			PackedCollection destination = new PackedCollection(shape(4), 0, destinationValues, 0, order);

			assertTrue("the source should be regular", source.getShape().isRegular());
			assertTrue("the source should carry no memory ordering", source.getMemOrdering() == null);
			assertNotNull("the destination should carry a memory ordering", destination.getMemOrdering());

			try {
				new CollectionProvider<>(source).into(destination).evaluate();
				throw new AssertionError("a regular source was flat-copied into an ordered destination");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("destination"));
			}

			double[] untouched = destinationValues.toArray(0, 4);
			for (int i = 0; i < untouched.length; i++) {
				assertEquals(0.0, untouched[i]);
			}
		}
	}

	/**
	 * A regular provider is refused when the destination's outer shape is regular but it inherits an
	 * {@link ExplicitIndexTraversalOrdering} from its delegate, because its logical reads still apply
	 * the ordering through {@link org.almostrealism.hardware.MemoryData#getMemOrdering()}.
	 */
	@Test(timeout = 10000)
	public void plainSourceIntoInheritedOrderingDestinationIsRefused() {
		try (PackedCollection source = pack(10.0, 20.0, 30.0, 40.0);
				PackedCollection indices = pack(2, 0, 3, 1);
				PackedCollection destinationValues = new PackedCollection(shape(4))) {
			ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(indices);
			PackedCollection ordered = new PackedCollection(shape(4), 0, destinationValues, 0, order);
			PackedCollection destination = new PackedCollection(shape(4), 0, ordered, 0);

			assertTrue("the destination's outer shape should be regular", destination.getShape().isRegular());
			assertNotNull("the destination should inherit the delegate's ordering", destination.getMemOrdering());

			try {
				new CollectionProvider<>(source).into(destination).evaluate();
				throw new AssertionError("a regular source was flat-copied into a destination inheriting an ordering");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("destination"));
			}
		}
	}

	/**
	 * A regular provider is refused when the destination is a rated view the source does not share.
	 *
	 * <p>A destination of {@code new TraversalPolicy(3).repeat(0, 4)} backs only 3 elements, while a
	 * regular 12-element source transfers 12, so the flat copy would overrun the destination and its
	 * logical reads would repeat the first three values regardless.</p>
	 */
	@Test(timeout = 10000)
	public void plainSourceIntoRatedDestinationIsRefused() {
		TraversalPolicy rated = new TraversalPolicy(3).repeat(0, 4);

		try (PackedCollection source = new PackedCollection(shape(12));
				PackedCollection destination = new PackedCollection(rated)) {
			assertTrue("the source should be regular", source.getShape().isRegular());
			assertFalse("the rated destination should be irregular", destination.getShape().isRegular());
			assertEquals(12, source.getShape().getTotalInputSize());
			assertEquals(3, destination.getMemLength());

			try {
				new CollectionProvider<>(source).into(destination).evaluate();
				throw new AssertionError("a regular source was flat-copied into a rated destination");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("destination"));
			}
		}
	}

	/**
	 * A regular provider copied into a regular destination of a different but equally sized shape is
	 * still accepted, and the values arrive in order.
	 */
	@Test(timeout = 10000)
	public void plainSourceIntoReshapedRegularDestinationCopies() {
		try (PackedCollection source = pack(10.0, 20.0, 30.0, 40.0);
				PackedCollection destination = new PackedCollection(shape(2, 2))) {
			new CollectionProvider<>(source).into(destination).evaluate();

			double[] copied = destination.toArray(0, 4);
			assertEquals(10.0, copied[0]);
			assertEquals(20.0, copied[1]);
			assertEquals(30.0, copied[2]);
			assertEquals(40.0, copied[3]);
		}
	}
}
