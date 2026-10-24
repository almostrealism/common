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

import io.almostrealism.code.ComputationBase;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.collect.DelegatedTraversalOrdering;
import io.almostrealism.collect.IndexSet;
import io.almostrealism.collect.RepeatTraversalOrdering;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Signature;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.ExplicitIndexTraversalOrdering;
import org.almostrealism.collect.IndexMaskTraversalOrdering;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests that a {@link PackedCollection} whose shape carries a {@link TraversalOrdering} is a
 * correct input to computations compiled into kernels: element-wise, reshape, subset, pad and
 * matrix multiplication. Each computation is evaluated on the CPU (native) backend and, where
 * the host provides one, on the GPU (Metal on macOS) backend.
 *
 * <p>The orderings covered are a compact {@link ExplicitIndexTraversalOrdering} (a lookup table
 * in which {@code -1} marks an absent element), an {@link IndexMaskTraversalOrdering} (a mask of
 * the logical indices that are present, searched for each lookup) and a full permutation by an
 * {@link ExplicitIndexTraversalOrdering}. Both of the first two represent the 2x2 matrix
 * {@code [[2, 0], [0, 3]]} holding only its two non-zero values. Every test asserts that the input
 * it builds still carries its ordering, because shape equality ignores the ordering and a lost
 * ordering would otherwise go unnoticed.</p>
 */
public class OrderedCollectionInputTests extends TestSuiteBase {

	/**
	 * Returns the backends to evaluate on: the CPU, and the GPU when one is available.
	 *
	 * @return the compute requirements selecting each backend
	 */
	protected List<ComputeRequirement> backends() {
		List<ComputeRequirement> backends = Stream.of(ComputeRequirement.CPU, ComputeRequirement.GPU)
				.filter(r -> Hardware.getLocalHardware().isAvailable(r))
				.collect(Collectors.toList());
		assertFalse("no backend is available", backends.isEmpty());
		return backends;
	}

	/**
	 * Builds the computation for each backend, evaluates it there, and compares the result with the
	 * expected values.
	 *
	 * @param expected    the expected values of the result, in logical order
	 * @param computation builds a fresh computation to evaluate
	 */
	protected void assertEvaluatesTo(double[] expected, Supplier<CollectionProducer> computation) {
		for (ComputeRequirement backend : backends()) {
			CollectionProducer producer = computation.get();
			((ComputationBase) producer).setComputeRequirements(List.of(backend));

			PackedCollection result = producer.evaluate();
			assertEquals("result size on " + backend, expected.length, result.getShape().getTotalSize());

			double[] actual = result.toArray(0, expected.length);
			for (int i = 0; i < expected.length; i++) {
				assertEquals("element " + i + " on " + backend, expected[i], actual[i]);
			}
		}
	}

	/**
	 * Wraps the given values in a collection read through the given ordering.
	 *
	 * @param shape  the logical shape of the ordered collection
	 * @param values the physical values
	 * @param order  the ordering mapping logical indices to physical positions
	 * @return the ordered collection, which is asserted to carry the ordering
	 */
	protected PackedCollection ordered(TraversalPolicy shape,
									   PackedCollection values, TraversalOrdering order) {
		PackedCollection ordered = new PackedCollection(shape, shape.getDimensions() - 1, values, 0, order);
		assertTrue("the input should carry its ordering", ordered.getShape().getOrder() == order);
		return ordered;
	}

	/** Returns {@code [[2, 0], [0, 3]]} stored compactly by an explicit index table. */
	protected PackedCollection compact() {
		return ordered(shape(2, 2), pack(2.0, 3.0), new ExplicitIndexTraversalOrdering(pack(0, -1, -1, 1)));
	}

	/** Returns {@code [[2, 0], [0, 3]]} stored compactly by a mask of the present indices. */
	protected PackedCollection mask() {
		return ordered(shape(2, 2), pack(2.0, 3.0), new IndexMaskTraversalOrdering(pack(0, 3)));
	}

	/** Returns {@code [30, 10, 40, 20]}, a full permutation of {@code [10, 20, 30, 40]}. */
	protected PackedCollection permutation() {
		return ordered(shape(4), pack(10.0, 20.0, 30.0, 40.0), new ExplicitIndexTraversalOrdering(pack(2, 0, 3, 1)));
	}

	/**
	 * A compact explicit-index ordering is read correctly by an element-wise kernel, with the
	 * absent elements reading as zero. This failed to compile before the index table was delivered
	 * to the kernel as an argument.
	 */
	@Test(timeout = 60000)
	public void elementwiseCompactOrdering() {
		PackedCollection compact = compact();
		assertEvaluatesTo(new double[] { 4.0, 0.0, 0.0, 6.0 }, () -> c(2).multiply(cp(compact)));
	}

	/**
	 * A mask ordering is read correctly by an element-wise kernel: the mask is searched
	 * symbolically inside the kernel rather than on the host.
	 */
	@Test(timeout = 60000)
	public void elementwiseMaskOrdering() {
		PackedCollection mask = mask();
		assertEvaluatesTo(new double[] { 4.0, 0.0, 0.0, 6.0 }, () -> c(2).multiply(cp(mask)));
	}

	/**
	 * Evaluates element-wise kernels over the compact and mask orderings under an
	 * {@link OperationProfileNode}, saved to {@code results/ordered_collection_input.xml}, so
	 * that the generated source can be inspected: the index table and the mask are read from
	 * kernel arguments rather than folded in on the host.
	 *
	 * @throws IOException if the profile cannot be saved
	 */
	@Test(timeout = 60000)
	public void profiledOrderedInputKernels() throws IOException {
		PackedCollection compact = compact();
		PackedCollection mask = mask();
		OperationProfileNode profile = new OperationProfileNode("ordered_collection_input");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			assertEvaluatesTo(new double[] { 4.0, 0.0, 0.0, 6.0 }, () -> c(2).multiply(cp(compact)));
			assertEvaluatesTo(new double[] { 6.0, 0.0, 0.0, 9.0 }, () -> c(3).multiply(cp(mask)));
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
		}

		profile.save("results/ordered_collection_input.xml");
	}

	/** A full permutation by an explicit index table is read correctly by an element-wise kernel. */
	@Test(timeout = 60000)
	public void elementwisePermutation() {
		PackedCollection permutation = permutation();
		assertEvaluatesTo(new double[] { 60.0, 20.0, 80.0, 40.0 }, () -> c(2).multiply(cp(permutation)));
	}

	/**
	 * A composition of a compact ordering with a permutation is read correctly by an element-wise
	 * kernel: both index tables are delivered to the kernel, present elements are found through
	 * both of them, and an absent element reads as zero. The permutation table is a view preceded
	 * by the entry {@code 5}, so a kernel that asked the permutation to map the negative position
	 * of an absent element would read past the two stored values instead of producing zero.
	 */
	@Test(timeout = 60000)
	public void elementwiseComposedOrdering() {
		DelegatedTraversalOrdering order = new DelegatedTraversalOrdering(
				new ExplicitIndexTraversalOrdering(pack(0, -1, 1)),
				new ExplicitIndexTraversalOrdering(pack(5, 1, 0).range(shape(2), 1)));
		PackedCollection composed = ordered(shape(3), pack(2.0, 3.0), order);

		assertEvaluatesTo(new double[] { 6.0, 0.0, 4.0 }, () -> c(2).multiply(cp(composed)));
	}

	/** An ordered input combined element-wise with a plain input keeps its own ordering. */
	@Test(timeout = 60000)
	public void elementwiseOrderedAndPlainInputs() {
		PackedCollection compact = compact();
		PackedCollection plain = pack(1.0, 2.0, 3.0, 4.0).reshape(shape(2, 2));
		assertEvaluatesTo(new double[] { 3.0, 2.0, 3.0, 7.0 }, () -> add(cp(compact), cp(plain)));
	}

	/** A reshaped ordered input is read through its ordering. */
	@Test(timeout = 60000)
	public void reshapeOrderedInput() {
		PackedCollection compact = compact();
		PackedCollection mask = mask();
		assertEvaluatesTo(new double[] { 4.0, 0.0, 0.0, 6.0 }, () -> c(2).multiply(reshape(shape(4), cp(compact))));
		assertEvaluatesTo(new double[] { 4.0, 0.0, 0.0, 6.0 }, () -> c(2).multiply(reshape(shape(4, 1), cp(mask))));
	}

	/** A subset of an ordered input selects the logical elements, not the stored ones. */
	@Test(timeout = 60000)
	public void subsetOrderedInput() {
		PackedCollection compact = compact();
		PackedCollection mask = mask();
		PackedCollection permutation = permutation();
		assertEvaluatesTo(new double[] { 0.0, 3.0 }, () -> subset(shape(1, 2), cp(compact), 1, 0));
		assertEvaluatesTo(new double[] { 0.0, 3.0 }, () -> subset(shape(2, 1), cp(mask), 0, 1));
		assertEvaluatesTo(new double[] { 10.0, 40.0 }, () -> subset(shape(2), cp(permutation), 1));
	}

	/** Padding an ordered input surrounds its logical elements with zeros. */
	@Test(timeout = 60000)
	public void padOrderedInput() {
		double[] expected = {
				0.0, 0.0, 0.0, 0.0,
				0.0, 2.0, 0.0, 0.0,
				0.0, 0.0, 3.0, 0.0,
				0.0, 0.0, 0.0, 0.0
		};

		PackedCollection compact = compact();
		PackedCollection mask = mask();
		assertEvaluatesTo(expected, () -> pad(new int[] { 0, 1 }, 1, cp(compact)));
		assertEvaluatesTo(expected, () -> pad(new int[] { 0, 1 }, 1, cp(mask)));
	}

	/** A matrix stored through an ordering is multiplied by a vector correctly. */
	@Test(timeout = 60000)
	public void matmulOrderedInput() {
		PackedCollection antiDiagonal = ordered(shape(2, 2), pack(2.0, 3.0),
				new ExplicitIndexTraversalOrdering(pack(-1, 0, 1, -1)));
		PackedCollection permuted = ordered(shape(2, 2), pack(10.0, 20.0, 30.0, 40.0),
				new ExplicitIndexTraversalOrdering(pack(2, 0, 3, 1)));
		PackedCollection maskedAntiDiagonal = ordered(shape(2, 2), pack(2.0, 3.0),
				new IndexMaskTraversalOrdering(pack(1, 2)));
		PackedCollection vector = pack(5.0, 7.0);

		assertEvaluatesTo(new double[] { 14.0, 15.0 }, () -> matmul(cp(antiDiagonal), cp(vector)));
		assertEvaluatesTo(new double[] { 220.0, 340.0 }, () -> matmul(cp(permuted), cp(vector)));
		assertEvaluatesTo(new double[] { 14.0, 15.0 }, () -> matmul(cp(maskedAntiDiagonal), cp(vector)));
	}

	/**
	 * Two inputs of equal shape and equal storage, differing only in the index table of their
	 * orderings, give their own results. Shape equality ignores the ordering, so a compiled kernel
	 * reused between them would read the index table it was compiled for.
	 */
	@Test(timeout = 60000)
	public void distinctIndexTablesAreNotConfused() {
		PackedCollection diagonal = compact();
		PackedCollection antiDiagonal = ordered(shape(2, 2), pack(2.0, 3.0),
				new ExplicitIndexTraversalOrdering(pack(-1, 0, 1, -1)));

		assertEvaluatesTo(new double[] { 4.0, 0.0, 0.0, 6.0 }, () -> c(2).multiply(cp(diagonal)));
		assertEvaluatesTo(new double[] { 0.0, 4.0, 6.0, 0.0 }, () -> c(2).multiply(cp(antiDiagonal)));
		assertEvaluatesTo(new double[] { 4.0, 0.0, 0.0, 6.0 }, () -> c(2).multiply(cp(diagonal)));
	}

	/**
	 * One provider of an ordered collection, shared by two computations that are both compiled
	 * before either is evaluated, is read through each kernel's own index table argument. The
	 * provider binds its ordering to the arguments of whichever kernel prepared it last, so each
	 * argument variable must keep the ordering it was created with.
	 */
	@Test(timeout = 60000)
	public void sharedOrderedProviderAcrossKernels() {
		CollectionProducer input = cp(compact());

		Evaluable<PackedCollection> product = c(2).multiply(input).get();
		Evaluable<PackedCollection> sum = c(3).add(input).get();

		assertEquals(4.0, product.evaluate().toDouble(0));
		assertEquals(0.0, product.evaluate().toDouble(1));
		assertEquals(6.0, product.evaluate().toDouble(3));
		assertEquals(5.0, sum.evaluate().toDouble(0));
		assertEquals(3.0, sum.evaluate().toDouble(1));
		assertEquals(6.0, sum.evaluate().toDouble(3));
	}

	/**
	 * A provider of a collection whose ordering reads an index table has no signature, so no kernel
	 * that reads it is reused, while a closed-form ordering contributes its own signature.
	 */
	@Test(timeout = 10000)
	public void orderingSignatures() {
		PackedCollection root = pack(2.0, 3.0, 1.0);
		PackedCollection repeated = new PackedCollection(shape(4, 3), 1, root, 0, new RepeatTraversalOrdering(3));
		PackedCollection plain = new PackedCollection(shape(4, 3));

		assertTrue("an index table ordering should have no signature", Signature.of(cp(compact())) == null);
		assertTrue("a mask ordering should have no signature", Signature.of(cp(mask())) == null);

		String repeatedSignature = Signature.of(cp(repeated));
		assertNotNull(repeatedSignature);
		assertTrue(repeatedSignature, repeatedSignature.contains("repeat:3"));
		assertFalse(repeatedSignature.equals(Signature.of(cp(plain))));
	}

	/**
	 * Membership follows the ordering on the host and through a reshape: an absent element of a
	 * compact ordering is not a member, while a present element is a member even though shape
	 * equality cannot tell the ordered and plain shapes apart. A plain shape's members are the
	 * indices within its size.
	 */
	@Test(timeout = 10000)
	public void membershipFollowsOrdering() {
		PackedCollection compact = compact();
		Producer<PackedCollection> reshaped = reshape(shape(4), cp(compact));
		PackedCollection plain = new PackedCollection(shape(2, 2));

		assertTrue(compact.containsIndex(0).orElseThrow());
		assertFalse(compact.containsIndex(1).orElseThrow());
		assertFalse(compact.containsIndex(2).orElseThrow());
		assertTrue(compact.containsIndex(3).orElseThrow());

		assertTrue(((IndexSet) reshaped).containsIndex(3).orElseThrow());
		assertFalse(((IndexSet) reshaped).containsIndex(1).orElseThrow());

		assertTrue(plain.containsIndex(1).orElseThrow());
		assertFalse(plain.containsIndex(4).orElseThrow());
		assertTrue(cp(plain).containsIndex(3).orElseThrow());
		assertFalse(cp(plain).containsIndex(4).orElseThrow());
		assertFalse(cp(compact).containsIndex(2).orElseThrow());
	}

	/**
	 * A traversable expression that knows nothing of its extent defines a value at every
	 * non-negative index, so membership no longer throws: a negative index (the position of an
	 * absent element) is not a member, and every non-negative index is, however large.
	 */
	@Test(timeout = 10000)
	public void traversableExpressionMembershipIsNonNegative() {
		TraversableExpression<Double> unbounded = new TraversableExpression<>() {
			@Override
			public Expression<Double> getValueAt(Expression<?> index) {
				return new DoubleConstant(1.0);
			}
		};

		assertFalse(unbounded.containsIndex(-1).orElseThrow());
		assertTrue(unbounded.containsIndex(0).orElseThrow());
		assertTrue(unbounded.containsIndex(Integer.MAX_VALUE).orElseThrow());
	}
}
