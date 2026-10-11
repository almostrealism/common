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

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.calculus.DeltaAlternate;
import org.almostrealism.calculus.VectorJacobianProduct;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

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
	 * {@link Algebraic#getConstant}, for both sums and products.
	 */
	@Test(timeout = 60000)
	public void constantOperandsFold() {
		CollectionProducer product = multiply(c(2.0), c(3.0));
		OptionalDouble productConstant = Algebraic.getConstant(product);
		Assert.assertTrue(productConstant.isPresent());
		Assert.assertEquals(6.0, productConstant.getAsDouble(), 0.0);
		assertValues(product.evaluate(), 6.0);

		CollectionProducer sum = c(2.0).add(c(3.0));
		OptionalDouble sumConstant = Algebraic.getConstant(sum);
		Assert.assertTrue(sumConstant.isPresent());
		Assert.assertEquals(5.0, sumConstant.getAsDouble(), 0.0);
		assertValues(sum.evaluate(), 5.0);

		Assert.assertFalse(Algebraic.getConstant(c(2.0).add(cp(pack(1.0)))).isPresent());
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
	 * A computation keeps the delta alternate and short-circuit it is given; a reshape hands
	 * the alternate to the computation it reshapes; a producer that has nowhere to keep
	 * either returns itself unchanged rather than failing.
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
		Assert.assertSame(alternate, ((DeltaAlternate) inner).getDeltaAlternate());

		CollectionProducer provided = cp(x);
		Assert.assertSame(provided, provided.withDeltaAlternate(alternate));
		Assert.assertSame(provided, provided.withShortCircuit(shortCircuit));
		Assert.assertSame(provided, provided.withVectorJacobianProduct((target, gradient) -> c(gradient)));
		Assert.assertNull(provided.attemptVectorJacobianProduct(cp(x), cp(x)));
	}

	/**
	 * A vector-Jacobian product declared through a reshape is kept by the reshaped
	 * computation and reached from either; the reshape declines a gradient whose size
	 * differs from its own, and the product declines a target it does not apply to.
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
		assertValues(inner.attemptVectorJacobianProduct(target, gradient).evaluate(), 2.0, -2.0, 1.0, 4.0);
		assertValues(reshaped.attemptVectorJacobianProduct(target, gradient).evaluate(), 2.0, -2.0, 1.0, 4.0);

		Assert.assertNull(reshaped.attemptVectorJacobianProduct(target, cp(pack(1.0, 2.0))));
		Assert.assertNull(reshaped.attemptVectorJacobianProduct(cp(x), gradient));
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
