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

package org.almostrealism.collect.computations.test;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Computable;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.calculus.DeltaAlternate;
import org.almostrealism.calculus.MultiTermDeltaStrategy;
import org.almostrealism.calculus.VectorJacobianProduct;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Verifies the polymorphic hooks that replaced type checks and casts in the collection
 * features: {@link Producer#attemptMultiply(double)},
 * {@link io.almostrealism.relation.Computable#isReduction()},
 * {@link io.almostrealism.relation.Computable#isDirectlyComputable()}, the
 * {@code with...} declarations on {@link CollectionProducer} (and their delegation
 * through {@link ReshapeProducer}), and {@link TraversalPolicy#forKernelLength}.
 *
 * @author Michael Murray
 */
public class ProducerHookTest extends TestSuiteBase implements MatrixFeatures {

	/**
	 * A fixed count, a kernel no longer than the count, and a kernel exactly the count all
	 * keep the shape; a longer kernel prepends one dimension for its repetitions.
	 */
	@Test(timeout = 30000)
	public void forKernelLength() {
		TraversalPolicy shape = shape(4, 3);

		Assert.assertSame(shape, shape.forKernelLength(4, true, 40));
		Assert.assertSame(shape, shape.forKernelLength(4, false, 4));
		Assert.assertSame(shape, shape.forKernelLength(4, false, 3));

		TraversalPolicy repeated = shape.forKernelLength(4, false, 12);
		Assert.assertEquals(3, repeated.getDimensions());
		Assert.assertEquals(3, repeated.length(0));
		Assert.assertEquals(4, repeated.length(1));
		Assert.assertEquals(3, repeated.length(2));
	}

	/**
	 * An arithmetic sequence scales itself by rescaling its parameters, and
	 * {@code multiply(double, Producer)} uses that representation; a producer without
	 * one (and is not constant) declines, so {@code multiply(double, Producer)} returns null
	 * and the product of producers forms the product instead.
	 */
	@Test(timeout = 60000)
	public void attemptMultiply() {
		ArithmeticSequenceComputation sequence =
				new ArithmeticSequenceComputation(shape(5), true, 1.0, 2.0);

		CollectionProducer scaled = sequence.attemptMultiply(3.0);
		Assert.assertTrue(scaled instanceof ArithmeticSequenceComputation);
		assertValues(scaled.evaluate(), 3.0, 9.0, 15.0, 21.0, 27.0);
		assertValues(multiply(3.0, sequence).evaluate(), 3.0, 9.0, 15.0, 21.0, 27.0);

		CollectionProducer values = cp(pack(1.0, 2.0, 3.0));
		Assert.assertNull(values.attemptMultiply(3.0));
		Assert.assertNull(multiply(3.0, values));
		assertValues(multiply(c(3.0), values).evaluate(), 3.0, 6.0, 9.0);
	}

	/**
	 * Constant operands are folded into a single constant through
	 * {@link Computable#getConstant()}, for both sums and products.
	 */
	@Test(timeout = 60000)
	public void constantOperandsFold() {
		CollectionProducer product = multiply(c(2.0), c(3.0));
		OptionalDouble productConstant = product.getConstant();
		Assert.assertTrue(productConstant.isPresent());
		Assert.assertEquals(6.0, productConstant.getAsDouble(), 0.0);
		assertValues(product.evaluate(), 6.0);

		CollectionProducer sum = c(2.0).add(c(3.0));
		OptionalDouble sumConstant = sum.getConstant();
		Assert.assertTrue(sumConstant.isPresent());
		Assert.assertEquals(5.0, sumConstant.getAsDouble(), 0.0);
		assertValues(sum.evaluate(), 5.0);

		Assert.assertFalse(c(2.0).add(cp(pack(1.0))).getConstant().isPresent());
	}

	/**
	 * Aggregations and weighted sums are reductions, also when reached through a reshape;
	 * element-wise computations and provided values are not.
	 */
	@Test(timeout = 60000)
	public void isReduction() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);

		Assert.assertFalse(cp(x).isReduction());
		Assert.assertFalse(cp(x).add(cp(x)).isReduction());

		CollectionSumComputation sum = new CollectionSumComputation(cp(x));
		Assert.assertTrue(sum.isReduction());
		Assert.assertTrue(new ReshapeProducer(shape(1, 1), sum).isReduction());

		CollectionProducer wide = matmul(p(new PackedCollection(shape(1200, 8))),
				cp(new PackedCollection(shape(8))));
		Assert.assertTrue(wide.isReduction());
	}

	/**
	 * Collection computations and provided values are directly computable, also through a
	 * reshape; a producer evaluated by a host function is not, and neither is a reshape of it.
	 */
	@Test(timeout = 30000)
	public void isDirectlyComputable() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);

		Assert.assertTrue(cp(x).isDirectlyComputable());
		Assert.assertTrue(cp(x).add(cp(x)).isDirectlyComputable());
		Assert.assertTrue(new ReshapeProducer(shape(2, 2), cp(x)).isDirectlyComputable());

		Function<Object[], PackedCollection> function = args -> x;
		DynamicCollectionProducer dynamic = new DynamicCollectionProducer(shape(4), function);
		Assert.assertFalse(dynamic.isDirectlyComputable());
		Assert.assertFalse(new ReshapeProducer(shape(2, 2), dynamic).isDirectlyComputable());
	}

	/**
	 * A computation keeps the delta alternate and short-circuit it is given; a reshape keeps
	 * the alternate itself and leaves the computation it reshapes unchanged; a producer that
	 * has nowhere to keep either returns itself unchanged rather than failing.
	 */
	@Test(timeout = 30000)
	public void withDeltaAlternateAndShortCircuit() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		CollectionProducer alternate = cp(x).multiply(2.0);

		CollectionProducer sum = cp(x).add(cp(x));
		Assert.assertSame(sum, sum.withDeltaAlternate(alternate));
		Assert.assertSame(alternate, ((DeltaAlternate) sum).getDeltaAlternate());

		Evaluable<PackedCollection> shortCircuit = args -> x;
		Assert.assertSame(sum, sum.withShortCircuit(shortCircuit));
		Assert.assertSame(shortCircuit, ((CollectionProducerComputationBase) sum).getShortCircuit());

		CollectionProducer inner = cp(x).add(cp(x));
		ReshapeProducer reshaped = new ReshapeProducer(shape(2, 2), inner);
		Assert.assertSame(reshaped, reshaped.withDeltaAlternate(alternate));
		Assert.assertSame(alternate, reshaped.getDeltaAlternate());
		Assert.assertNull(((DeltaAlternate) inner).getDeltaAlternate());

		CollectionProducer provided = cp(x);
		Assert.assertSame(provided, provided.withDeltaAlternate(alternate));
		Assert.assertSame(alternate, provided.getDeltaAlternate());

		Function<Object[], PackedCollection> function = args -> x;
		CollectionProducer dynamic = new DynamicCollectionProducer(shape(4), function);
		Assert.assertSame(dynamic, dynamic.withDeltaAlternate(alternate));
		Assert.assertNull(dynamic.getDeltaAlternate());
		Assert.assertSame(dynamic, dynamic.withShortCircuit(shortCircuit));
		Assert.assertSame(dynamic, dynamic.withVectorJacobianProduct((target, gradient) -> c(gradient)));
		Assert.assertNull(dynamic.attemptVectorJacobianProduct(cp(x), cp(x)));
	}

	/**
	 * A vector-Jacobian product declared on a reshape is kept by the reshape, not by the
	 * computation it reshapes; the reshape declines a gradient whose size differs from its
	 * own, and the product declines a target it does not apply to.
	 */
	@Test(timeout = 60000)
	public void vectorJacobianProductThroughReshape() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		Producer<PackedCollection> target = cp(x);
		VectorJacobianProduct product = (t, gradient) -> t == target ? c(gradient).multiply(2.0) : null;

		CollectionProducer inner = cp(x).add(cp(x));
		ReshapeProducer reshaped = new ReshapeProducer(shape(2, 2), inner);
		Assert.assertSame(reshaped, reshaped.withVectorJacobianProduct(product));

		Producer<PackedCollection> gradient = cp(pack(1.0, -1.0, 0.5, 2.0));
		assertValues(reshaped.attemptVectorJacobianProduct(target, gradient).evaluate(), 2.0, -2.0, 1.0, 4.0);
		Assert.assertNull(inner.attemptVectorJacobianProduct(target, gradient));

		Assert.assertNull(reshaped.attemptVectorJacobianProduct(target, cp(pack(1.0, 2.0))));
		Assert.assertNull(reshaped.attemptVectorJacobianProduct(cp(x), gradient));
	}

	/**
	 * A product the underlying computation declares itself is still reached through a
	 * reshape that declares none, and one the reshape declares takes precedence over it.
	 */
	@Test(timeout = 60000)
	public void reshapeDefersToUnderlyingVectorJacobianProduct() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		Producer<PackedCollection> target = cp(x);
		Producer<PackedCollection> gradient = cp(pack(1.0, -1.0, 0.5, 2.0));

		CollectionProducer inner = cp(x).add(cp(x))
				.withVectorJacobianProduct((t, g) -> t == target ? c(g).multiply(2.0) : null);
		ReshapeProducer reshaped = new ReshapeProducer(shape(2, 2), inner);
		assertValues(reshaped.attemptVectorJacobianProduct(target, gradient).evaluate(), 2.0, -2.0, 1.0, 4.0);

		reshaped.withVectorJacobianProduct((t, g) -> t == target ? c(g).multiply(3.0) : null);
		assertValues(reshaped.attemptVectorJacobianProduct(target, gradient).evaluate(), 3.0, -3.0, 1.5, 6.0);
		assertValues(inner.attemptVectorJacobianProduct(target, gradient).evaluate(), 2.0, -2.0, 1.0, 4.0);
	}

	/**
	 * Declarations made on a reshape do not leak onto a computation shared with another
	 * consumer, survive a further reshape or traversal (which wraps the computation rather
	 * than the reshape) and regeneration, and the alternate's delta is the one used, in
	 * this reshape's shape followed by the target's.
	 */
	@Test(timeout = 60000)
	public void reshapeDeclarationsAreNotShared() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		Producer<PackedCollection> target = cp(x);
		Producer<PackedCollection> gradient = cp(pack(1.0, -1.0, 0.5, 2.0));
		VectorJacobianProduct product = (t, g) -> t == target ? c(g).multiply(5.0) : null;

		CollectionProducer shared = c(target).multiply(2.0);
		ReshapeProducer declaring = new ReshapeProducer(shape(2, 2), shared);
		ReshapeProducer other = new ReshapeProducer(shape(4, 1), shared);
		CollectionProducer alternate = c(target).multiply(3.0).reshape(shape(2, 2));
		declaring.withVectorJacobianProduct(product).withDeltaAlternate(alternate);

		Assert.assertNull(other.attemptVectorJacobianProduct(target, gradient));
		Assert.assertNull(other.getDeltaAlternate());
		Assert.assertNull(shared.getDeltaAlternate());

		CollectionProducer delta = declaring.delta(target);
		Assert.assertEquals(List.of(2, 2, 4), List.of(delta.getShape().length(0),
				delta.getShape().length(1), delta.getShape().length(2)));
		assertTripledIdentity(delta);
		assertTripledIdentity(shared.delta(target).multiply(1.5));

		CollectionProducer flattened = declaring.reshape(shape(4));
		assertValues(flattened.attemptVectorJacobianProduct(target, gradient).evaluate(), 5.0, -5.0, 2.5, 10.0);
		Assert.assertEquals(shape(4), flattened.getDeltaAlternate().getShape());
		assertTripledIdentity(flattened.delta(target));

		CollectionProducer traversed = declaring.traverse(1);
		assertValues(traversed.attemptVectorJacobianProduct(target, gradient).evaluate(), 5.0, -5.0, 2.5, 10.0);
		assertTripledIdentity(traversed.delta(target));

		ReshapeProducer generated = declaring.generate(List.of((Process<?, ?>) shared));
		assertValues(generated.attemptVectorJacobianProduct(target, gradient).evaluate(), 5.0, -5.0, 2.5, 10.0);
		Assert.assertSame(alternate, generated.getDeltaAlternate());
	}

	/** Asserts that a delta with respect to a four-element target is three times the identity. */
	private void assertTripledIdentity(CollectionProducer delta) {
		assertValues(delta.evaluate(), 3.0, 0.0, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0,
				0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, 3.0);
	}

	/**
	 * Producer matching: a reshape presents the value of the computation it wraps; a
	 * producer, and anything referring to it, depends on itself, including a producer
	 * that is neither a value source nor has any input that could contain it; inputs are
	 * searched at any depth; and an input found through several operands is ambiguous.
	 */
	@Test(timeout = 60000)
	public void producerMatching() {
		Producer<PackedCollection> a = p(pack(1.0, 2.0, 3.0, 4.0));
		Producer<PackedCollection> b = p(pack(5.0, 6.0, 7.0, 8.0));
		CollectionProducer scaled = c(a).multiply(2.0);
		CollectionProducer reshaped = scaled.reshape(shape(2, 2));

		Assert.assertTrue(a.isMatchRoot());
		Assert.assertFalse(scaled.isMatchRoot());
		Assert.assertSame(scaled, reshaped.getMatchRoot());

		Assert.assertTrue(a.cannotMatch(b));
		Assert.assertFalse(a.cannotMatch(a));
		Assert.assertTrue(scaled.cannotMatch(b));
		Assert.assertFalse(scaled.cannotMatch(a));

		Assert.assertTrue(scaled.refersTo(scaled));
		Assert.assertTrue(reshaped.refersTo(scaled));
		Assert.assertFalse(scaled.cannotMatch(scaled));
		Assert.assertFalse(reshaped.cannotMatch(scaled));
		Assert.assertFalse(scaled.cannotMatch(reshaped));

		Assert.assertEquals(1, scaled.matchingInputs(a).size());
		Assert.assertTrue(scaled.matchInput(b).isEmpty());
		Assert.assertTrue(reshaped.containsMatch(a));
		Assert.assertFalse(reshaped.containsMatch(b));

		CollectionProducer sum = scaled.add(c(a));
		Assert.assertEquals(2, sum.matchingInputs(a).size());
		Assert.assertNull(sum.matchInput(a));
		Assert.assertFalse(sum.cannotMatch(a));

		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		Function<Object[], PackedCollection> function = args -> x;
		DynamicCollectionProducer dynamic = new DynamicCollectionProducer(shape(4), function);
		Assert.assertFalse(dynamic.isMatchRoot());
		Assert.assertTrue(dynamic.matchingInputs(dynamic).isEmpty());
		Assert.assertFalse(dynamic.cannotMatch(dynamic));
		Assert.assertFalse(dynamic.reshape(shape(2, 2)).cannotMatch(dynamic));
		Assert.assertTrue(dynamic.cannotMatch(a));
	}

	/**
	 * The direct delta of a producer with respect to itself is the identity, not the zeros
	 * of an independent producer, also for a producer that is neither a value source nor
	 * has any inputs; with respect to an unrelated value it is zeros.
	 */
	@Test(timeout = 60000)
	public void directDeltaWithRespectToItself() {
		CollectionProducer scaled = cp(pack(1.0, 2.0, 3.0)).multiply(2.0);
		assertValues(scaled.attemptDirectDelta(scaled).evaluate(),
				1.0, 0.0, 0.0,
				0.0, 1.0, 0.0,
				0.0, 0.0, 1.0);
		assertValues(scaled.attemptDirectDelta(cp(pack(4.0, 5.0))).evaluate(),
				0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

		PackedCollection x = pack(1.0, 2.0);
		Function<Object[], PackedCollection> function = args -> x;
		DynamicCollectionProducer dynamic = new DynamicCollectionProducer(shape(2), function);
		assertValues(dynamic.attemptDirectDelta(dynamic).evaluate(),
				1.0, 0.0,
				0.0, 1.0);
	}

	/**
	 * A multi-term strategy returns a Jacobian shaped as the computation's shape followed by
	 * the target's shape: zeros of that shape for a target in none of the terms, and the
	 * identity for an additive term; {@link MultiTermDeltaStrategy#NONE} declines.
	 */
	@Test(timeout = 60000)
	public void multiTermDeltaShapes() {
		CollectionProducer a = cp(pack(1.0, 2.0, 3.0));
		CollectionProducer sum = a.add(cp(pack(4.0, 5.0, 6.0)));
		Assert.assertTrue(sum instanceof CollectionProducerComputation);
		CollectionProducerComputation computation = (CollectionProducerComputation) sum;

		CollectionProducer independent =
				MultiTermDeltaStrategy.IGNORE.delta(computation, cp(pack(7.0, 8.0)));
		Assert.assertEquals(2, independent.getShape().getDimensions());
		Assert.assertEquals(3, independent.getShape().length(0));
		Assert.assertEquals(2, independent.getShape().length(1));
		assertValues(independent.evaluate(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

		CollectionProducer term = MultiTermDeltaStrategy.IGNORE.delta(computation, a);
		Assert.assertEquals(3, term.getShape().length(0));
		Assert.assertEquals(3, term.getShape().length(1));
		assertValues(term.evaluate(),
				1.0, 0.0, 0.0,
				0.0, 1.0, 0.0,
				0.0, 0.0, 1.0);

		Assert.assertNull(MultiTermDeltaStrategy.NONE.delta(computation, a));
	}

	/**
	 * {@link MultiTermDeltaStrategy#COMBINE} differentiates a product with respect to one
	 * factor as the diagonal of the other factors, and declines (returns null) when the
	 * target is more than one of the terms.
	 */
	@Test(timeout = 60000)
	public void multiTermDeltaCombine() {
		CollectionProducer a = cp(pack(1.0, 2.0, 3.0));
		CollectionProducer product = a.multiply(cp(pack(4.0, 5.0, 6.0)));
		Assert.assertTrue(product instanceof CollectionProducerComputation);

		CollectionProducer diagonal =
				MultiTermDeltaStrategy.COMBINE.delta((CollectionProducerComputation) product, a);
		Assert.assertEquals(3, diagonal.getShape().length(0));
		Assert.assertEquals(3, diagonal.getShape().length(1));
		assertValues(diagonal.evaluate(),
				4.0, 0.0, 0.0,
				0.0, 5.0, 0.0,
				0.0, 0.0, 6.0);

		CollectionProducer twice = a.add(a);
		Assert.assertTrue(twice instanceof CollectionProducerComputation);
		Assert.assertNull(MultiTermDeltaStrategy.COMBINE.delta((CollectionProducerComputation) twice, a));
	}

	/**
	 * Asserts that a collection holds exactly the given values.
	 *
	 * @param actual   the evaluated collection
	 * @param expected the expected values, in order
	 */
	private void assertValues(PackedCollection actual, double... expected) {
		Assert.assertEquals(expected.length, actual.getShape().getTotalSize());
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals("value[" + i + "]", expected[i], actual.toDouble(i), 1e-6);
		}
	}
}
