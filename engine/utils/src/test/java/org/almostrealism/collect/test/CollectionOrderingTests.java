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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.collect.DelegatedTraversalOrdering;
import io.almostrealism.collect.RepeatTraversalOrdering;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import org.almostrealism.collect.ExplicitIndexTraversalOrdering;
import org.almostrealism.collect.computations.CollectionProvider;
import org.almostrealism.collect.IndexMaskTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tests for collection traversal ordering implementations.
 */
public class CollectionOrderingTests extends TestSuiteBase {

	/**
	 * Tests repeat ordering traversal.
	 */
	@Test(timeout = 10000)
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
	 * A composition whose first ordering is compact does not ask its second ordering to map the
	 * negative position of an absent element: on the host that would read the second ordering's
	 * index table out of bounds. The second index table here is a view preceded by a non-negative
	 * entry, so a read at position -1 would report the absent element as present at position 5.
	 */
	@Test(timeout = 10000)
	public void composedCompactOrderingKeepsAbsentElementsAbsent() {
		ExplicitIndexTraversalOrdering compact = new ExplicitIndexTraversalOrdering(pack(0, -1, 1));
		ExplicitIndexTraversalOrdering permutation =
				new ExplicitIndexTraversalOrdering(pack(5, 1, 0).range(shape(2), 1));
		DelegatedTraversalOrdering order = new DelegatedTraversalOrdering(compact, permutation);

		assertEquals(1, order.indexOf(0));
		assertEquals(-1, order.indexOf(1));
		assertEquals(0, order.indexOf(2));

		assertTrue(order.containsIndex(new IntegerConstant(0)).booleanValue().orElseThrow());
		assertFalse(order.containsIndex(new IntegerConstant(1)).booleanValue().orElseThrow());
		assertTrue(order.containsIndex(new IntegerConstant(2)).booleanValue().orElseThrow());
	}

	/**
	 * The length of a composition is the number of physical positions its second ordering
	 * addresses, whatever the extent of its first ordering, and is unbounded when the second
	 * ordering is.
	 */
	@Test(timeout = 10000)
	public void composedOrderingLengthIsSecondOrderingLength() {
		ExplicitIndexTraversalOrdering compact = new ExplicitIndexTraversalOrdering(pack(0, -1, 4));
		ExplicitIndexTraversalOrdering permutation = new ExplicitIndexTraversalOrdering(pack(1, 0));

		assertEquals(2, new DelegatedTraversalOrdering(compact, permutation).getLength().getAsInt());
		assertEquals(3, new DelegatedTraversalOrdering(permutation, new RepeatTraversalOrdering(3))
				.getLength().getAsInt());
		assertFalse(new DelegatedTraversalOrdering(compact, idx -> idx).getLength().isPresent());
	}

	/**
	 * A composition has a signature only when both of its orderings do, so a kernel that reads
	 * an index table through either half of a composition is never reused.
	 */
	@Test(timeout = 10000)
	public void composedOrderingSignature() {
		RepeatTraversalOrdering period3 = new RepeatTraversalOrdering(3);
		RepeatTraversalOrdering period2 = new RepeatTraversalOrdering(2);
		ExplicitIndexTraversalOrdering table = new ExplicitIndexTraversalOrdering(pack(1, 0));

		assertEquals("(repeat:3)->(repeat:2)", new DelegatedTraversalOrdering(period3, period2).signature());
		assertNotEquals(new DelegatedTraversalOrdering(period3, period2).signature(),
				new DelegatedTraversalOrdering(period2, period3).signature());
		assertTrue(new DelegatedTraversalOrdering(period3, table).signature() == null);
		assertTrue(new DelegatedTraversalOrdering(table, period3).signature() == null);
	}

	/**
	 * Every logical index of a repeat ordering has a position within the period, so its
	 * membership test is the constant {@code true} even for a variable index, and reads of a
	 * repeated collection carry no gate. A composition with a compact first ordering still
	 * excludes the absent elements of that ordering.
	 */
	@Test(timeout = 10000)
	public void repeatOrderingContainsEveryIndex() {
		RepeatTraversalOrdering period3 = new RepeatTraversalOrdering(3);
		Expression<Integer> index = new StaticReference<>(Integer.class, "i");

		assertTrue(period3.containsIndex(index).booleanValue().orElseThrow());
		assertTrue(period3.containsIndex(new IntegerConstant(7)).booleanValue().orElseThrow());
		assertEquals(1, period3.indexOf(7));

		DelegatedTraversalOrdering composed = new DelegatedTraversalOrdering(
				new ExplicitIndexTraversalOrdering(pack(4, -1)), period3);
		assertTrue(composed.containsIndex(new IntegerConstant(0)).booleanValue().orElseThrow());
		assertFalse(composed.containsIndex(new IntegerConstant(1)).booleanValue().orElseThrow());
		assertEquals(1, composed.indexOf(0));
		assertEquals(-1, composed.indexOf(1));
	}

	/**
	 * Binding a composition binds both of its orderings: each index table is requested from the
	 * provider, and a composition whose orderings are unchanged by binding is returned as is.
	 */
	@Test(timeout = 10000)
	public void composedOrderingBindsBothOrderings() {
		List<Supplier<?>> requested = new ArrayList<>();
		ArgumentProvider provider = OrderedCollectionInputTests.recordingProvider(null, requested);

		DelegatedTraversalOrdering closedForm = new DelegatedTraversalOrdering(
				new RepeatTraversalOrdering(3), new RepeatTraversalOrdering(2));
		assertTrue(closedForm.bind(provider) == closedForm);
		assertEquals(0, requested.size());

		DelegatedTraversalOrdering tables = new DelegatedTraversalOrdering(
				new ExplicitIndexTraversalOrdering(pack(1, 0)),
				new IndexMaskTraversalOrdering(pack(0, 3)));
		assertTrue(tables.bind(provider) == tables);
		assertEquals(2, requested.size());
	}

	/**
	 * A bound {@link ExplicitIndexTraversalOrdering} is still an explicit index ordering: it
	 * reads the argument the provider supplies, reports the length of the original index
	 * collection, and binding it again requests the index collection from the new provider.
	 */
	@Test(timeout = 10000)
	public void explicitIndexBindingKeepsOrdering() {
		List<Supplier<?>> requested = new ArrayList<>();
		PackedCollection argument = pack(1, 0, 0);
		ArgumentProvider provider = OrderedCollectionInputTests.recordingProvider(argument, requested);

		ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(pack(2, 0, -1));
		TraversalOrdering bound = order.bind(provider);
		assertEquals(3, bound.getLength().getAsInt());
		assertEquals(1, bound.indexOf(0));
		assertEquals(0, bound.indexOf(2));

		bound.bind(provider);
		assertEquals(2, requested.size());
	}

	/**
	 * The length of an {@link ExplicitIndexTraversalOrdering} is the number of physical positions
	 * its indices address, one more than the largest index, since that is how much backing memory
	 * the ordering reads.
	 */
	@Test(timeout = 10000)
	public void explicitIndexLengthCountsPhysicalPositions() {
		assertEquals(4, new ExplicitIndexTraversalOrdering(pack(2, 0, 3, 1)).getLength().getAsInt());
		assertEquals(2, new ExplicitIndexTraversalOrdering(pack(0, -1, -1, 1)).getLength().getAsInt());
		assertEquals(0, new ExplicitIndexTraversalOrdering(pack(-1, -1)).getLength().getAsInt());
	}

	/**
	 * A range through an {@link ExplicitIndexTraversalOrdering} is bounds checked against every
	 * position the ordering addresses: a range whose largest index would fall past the end of the
	 * collection is refused rather than reading beyond it.
	 */
	@Test(timeout = 10000)
	public void explicitIndexRangeChecksEveryPosition() {
		PackedCollection values = pack(10.0, 20.0, 30.0, 40.0, 50.0);
		ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(pack(2, 0, 3, 1));

		PackedCollection permuted = values.range(shape(4).withOrder(order), 1);
		assertEquals(40.0, permuted.toDouble(0));
		assertEquals(20.0, permuted.toDouble(1));
		assertEquals(50.0, permuted.toDouble(2));
		assertEquals(30.0, permuted.toDouble(3));

		try {
			values.range(shape(4).withOrder(order), 2);
			throw new AssertionError("a range reading past the end of the collection was accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("exceeds"));
		}
	}

	/**
	 * The search of an {@link IndexMaskTraversalOrdering} is symbolic: for an index that is not a
	 * compile-time constant it builds an expression rather than scanning the mask on the host, and
	 * for a constant index it folds to the position, or to {@code -1} for an absent index.
	 */
	@Test(timeout = 10000)
	public void maskIndexOfIsSymbolic() {
		IndexMaskTraversalOrdering order = new IndexMaskTraversalOrdering(pack(0, 3));

		assertEquals(0, order.indexOf(0));
		assertEquals(-1, order.indexOf(1));
		assertEquals(-1, order.indexOf(2));
		assertEquals(1, order.indexOf(3));

		Expression<Integer> index = new StaticReference<>(Integer.class, "i");
		Expression<Integer> position = order.indexOf(index);
		assertFalse("a variable index should not fold to a constant", position.intValue().isPresent());
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

	/**
	 * {@link org.almostrealism.hardware.MemoryData#hasSameElements} recognizes views of one memory
	 * that present the same elements in the same order, and rejects views that differ in offset,
	 * length or {@link org.almostrealism.hardware.MemoryData#getMemOrdering() memory ordering}, as
	 * well as a distinct memory holding equal values.
	 */
	@Test(timeout = 10000)
	public void hasSameElements() {
		try (PackedCollection values = pack(10.0, 20.0, 30.0, 40.0);
				PackedCollection copy = pack(10.0, 20.0, 30.0, 40.0);
				PackedCollection indices = pack(2, 0, 3, 1)) {
			ExplicitIndexTraversalOrdering order = new ExplicitIndexTraversalOrdering(indices);
			PackedCollection ordered = new PackedCollection(shape(4), 0, values, 0, order);
			PackedCollection orderedAgain = new PackedCollection(shape(4), 0, values, 0, order);
			PackedCollection plainView = new PackedCollection(shape(4), 0, values, 0);

			assertTrue(values.hasSameElements(values));
			assertFalse(values.hasSameElements(null));
			assertTrue(values.hasSameElements(values.reshape(shape(2, 2))));
			assertTrue(values.reshape(shape(2, 2)).hasSameElements(values));
			assertTrue(values.hasSameElements(plainView));
			assertTrue(values.reshape(shape(2, 2)).hasSameElements(plainView.reshape(shape(4, 1))));

			assertFalse("a leading part has a different length",
					values.hasSameElements(values.range(shape(2), 0)));
			assertFalse("a trailing part has a different offset",
					values.range(shape(2), 0).hasSameElements(values.range(shape(2), 2)));
			assertFalse("equal values in distinct memory are different elements",
					values.hasSameElements(copy));

			assertNotNull(ordered.getMemOrdering());
			assertFalse("an ordered view presents the elements in a different order",
					ordered.hasSameElements(plainView));
			assertFalse(plainView.hasSameElements(ordered));
			assertTrue("views sharing one ordering present the same elements",
					ordered.hasSameElements(orderedAgain));
		}
	}
}
