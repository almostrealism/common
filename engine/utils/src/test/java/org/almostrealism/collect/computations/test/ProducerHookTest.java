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
import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.collect.computations.CollectionAddComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProductComputation;
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
 * features: {@link CollectionProducer#multiply(double)},
 * {@link CollectionProducer#isReduction()}, the short-circuit declaration on a computation,
 * producer matching, the identity-determined cases of {@link CollectionProducer#delta}
 * (and its delegation through {@link ReshapeProducer}), and
 * {@link TraversalPolicy#forKernelLength}.
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
	 * An arithmetic sequence scales itself by rescaling its parameters, and the product
	 * of a constant with it, in either order, uses that representation; a producer without
	 * one is scaled by the product with the constant.
	 */
	@Test(timeout = 60000)
	public void multiplyByConstant() {
		ArithmeticSequenceComputation sequence =
				new ArithmeticSequenceComputation(shape(5), true, 1.0, 2.0);

		CollectionProducer scaled = sequence.multiply(3.0);
		Assert.assertTrue(scaled instanceof ArithmeticSequenceComputation);
		assertValues(scaled.evaluate(), 3.0, 9.0, 15.0, 21.0, 27.0);
		assertValues(multiply(3.0, sequence).evaluate(), 3.0, 9.0, 15.0, 21.0, 27.0);

		CollectionProducer left = multiply(c(-2.0), sequence);
		Assert.assertTrue(left instanceof ArithmeticSequenceComputation);
		assertValues(left.evaluate(), -2.0, -6.0, -10.0, -14.0, -18.0);

		CollectionProducer right = multiply(sequence, c(-2.0));
		Assert.assertTrue(right instanceof ArithmeticSequenceComputation);
		assertValues(right.evaluate(), -2.0, -6.0, -10.0, -14.0, -18.0);

		CollectionProducer values = cp(pack(1.0, 2.0, 3.0));
		Assert.assertFalse(values.multiply(3.0) instanceof ArithmeticSequenceComputation);
		assertValues(values.multiply(3.0).evaluate(), 3.0, 6.0, 9.0);
		assertValues(multiply(3.0, values).evaluate(), 3.0, 6.0, 9.0);
		assertValues(multiply(c(3.0), values).evaluate(), 3.0, 6.0, 9.0);
		Assert.assertSame(values, values.multiply(1.0));
		Assert.assertTrue(Algebraic.isZero(values.multiply(0.0)));
	}

	/**
	 * Constant operands are folded into a single constant through
	 * {@link Algebraic#getConstant()}, for both sums and products.
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
	 * The algebraic simplifications of a product apply to a producer evaluated by a host
	 * function as they do to any other: multiplying it by one leaves it unchanged, and
	 * multiplying it by another constant scales its values.
	 */
	@Test(timeout = 30000)
	public void multiplyHostFunction() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		Function<Object[], PackedCollection> function = args -> x;
		DynamicCollectionProducer dynamic = new DynamicCollectionProducer(shape(4), function);

		Assert.assertSame(dynamic, multiply(c(1.0), dynamic));
		assertValues(multiply(c(2.0), dynamic).evaluate(), 2.0, 4.0, 6.0, 8.0);
		assertValues(multiply(dynamic, cp(x)).evaluate(), 1.0, 4.0, 9.0, 16.0);
	}

	/**
	 * A computation keeps the short-circuit it is given; a producer that has nowhere to
	 * keep one returns itself unchanged rather than failing.
	 */
	@Test(timeout = 30000)
	public void withShortCircuit() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);

		CollectionProducer sum = cp(x).add(cp(x));
		Evaluable<PackedCollection> shortCircuit = args -> x;
		Assert.assertSame(sum, sum.withShortCircuit(shortCircuit));
		Assert.assertSame(shortCircuit, ((CollectionProducerComputationBase) sum).getShortCircuit());

		Function<Object[], PackedCollection> function = args -> x;
		CollectionProducer dynamic = new DynamicCollectionProducer(shape(4), function);
		Assert.assertSame(dynamic, dynamic.withShortCircuit(shortCircuit));
	}

	/**
	 * The reduced delta of a reshape is that of the computation it reshapes: the
	 * output-reduced delta takes a weight in the reshape's shape and has the target's
	 * shape, and the input-reduced delta takes a tangent of the target and has the
	 * reshape's shape.
	 */
	@Test(timeout = 60000)
	public void reducedDeltaThroughReshape() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		Producer<PackedCollection> target = cp(x);

		CollectionProducer inner = cp(x).add(cp(x));
		ReshapeProducer reshaped = new ReshapeProducer(shape(2, 2), inner);

		CollectionProducer gradient = c(shape(2, 2), 1.0, -1.0, 0.5, 2.0);
		CollectionProducer out = reshaped.delta(target, ReduceAxis.OUT, gradient);
		Assert.assertEquals(shape(4), out.getShape());
		assertValues(out.evaluate(), 2.0, -2.0, 1.0, 4.0);

		CollectionProducer in = reshaped.delta(target, ReduceAxis.IN, cp(pack(1.0, -1.0, 0.5, 2.0)));
		Assert.assertEquals(shape(2, 2), in.getShape());
		assertValues(in.evaluate(), 2.0, -2.0, 1.0, 4.0);
	}

	/**
	 * The Jacobian of a reshape is that of the computation it reshapes, in the reshape's
	 * shape followed by the target's, also through a further reshape or traversal (which
	 * wraps the computation rather than the reshape) and regeneration.
	 */
	@Test(timeout = 60000)
	public void jacobianThroughReshape() {
		PackedCollection x = pack(1.0, 2.0, 3.0, 4.0);
		Producer<PackedCollection> target = cp(x);

		CollectionProducer shared = c(target).multiply(3.0);
		ReshapeProducer reshaped = new ReshapeProducer(shape(2, 2), shared);

		CollectionProducer delta = reshaped.delta(target);
		Assert.assertEquals(List.of(2, 2, 4), List.of(delta.getShape().length(0),
				delta.getShape().length(1), delta.getShape().length(2)));
		assertTripledIdentity(delta);
		assertTripledIdentity(shared.delta(target));
		assertTripledIdentity(reshaped.reshape(shape(4)).delta(target));
		assertTripledIdentity(reshaped.traverse(1).delta(target));
		assertTripledIdentity(reshaped.generate(List.of((Process<?, ?>) shared)).delta(target));
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
	 * Providers of different views of one collection match when the views present the same
	 * elements in the same order: {@code cp(c.reshape(...))} refers to {@code cp(c)}, and
	 * {@code cp(c).reshape(...)} does too, while a view of part of the collection and a
	 * different collection of the same shape do not. The derivative of a computation that
	 * reads the reshaped view, taken with respect to {@code cp(c)}, is therefore not zero.
	 */
	@Test(timeout = 60000)
	public void providerViewMatching() {
		PackedCollection values = pack(1.0, 2.0, 3.0, 4.0);
		Producer<PackedCollection> provider = cp(values);
		Producer<PackedCollection> reshapedValue = cp(values.reshape(shape(2, 2)));

		Assert.assertTrue(reshapedValue.refersTo(provider));
		Assert.assertTrue(provider.refersTo(reshapedValue));
		Assert.assertTrue(cp(values).reshape(shape(2, 2)).refersTo(reshapedValue));
		Assert.assertFalse(reshapedValue.cannotMatch(provider));

		Assert.assertFalse(cp(values.range(shape(2), 2)).refersTo(provider));
		Assert.assertTrue(cp(values.range(shape(2), 2)).cannotMatch(provider));
		Assert.assertFalse(cp(pack(1.0, 2.0, 3.0, 4.0)).refersTo(provider));

		CollectionProducer delta = c(reshapedValue).multiply(3.0).delta(provider);
		assertTripledIdentity(delta);
	}

	/**
	 * A dynamic producer computed on the host from producer arguments depends on those
	 * arguments, even though they are not its process children: it contains a match for an
	 * argument (and for an input of an argument), is not independent of it, and its direct
	 * delta with respect to it is not the zeros of an independent producer. It remains
	 * independent of an unrelated value.
	 */
	@Test(timeout = 60000)
	public void dynamicProducerArgumentsAreMatched() {
		Producer<PackedCollection> a = p(pack(1.0, 2.0, 3.0, 4.0));
		Producer<PackedCollection> b = p(pack(5.0, 6.0, 7.0, 8.0));
		CollectionProducer scaled = c(a).multiply(2.0);

		Function<PackedCollection[], Function<Object[], PackedCollection>> function =
				inputs -> args -> inputs[0];
		DynamicCollectionProducer dynamic =
				new DynamicCollectionProducer(shape(4), function, false, true, scaled);

		Assert.assertEquals(1, dynamic.matchingInputs(a).size());
		Assert.assertSame(scaled, dynamic.matchingInputs(scaled).get(0));
		Assert.assertTrue(dynamic.containsMatch(a));
		Assert.assertFalse(dynamic.cannotMatch(a));
		Assert.assertFalse(dynamic.reshape(shape(2, 2)).cannotMatch(a));

		Assert.assertTrue(dynamic.matchingInputs(b).isEmpty());
		Assert.assertFalse(dynamic.containsMatch(b));
		Assert.assertTrue(dynamic.cannotMatch(b));
		assertValues(dynamic.delta(b).evaluate(), new double[16]);

		assertValues(dynamic.evaluate(), 2.0, 4.0, 6.0, 8.0);
	}

	/**
	 * A product with an operand that has a shape but is neither a {@link CollectionProducer}
	 * nor a {@link Process} is differentiated with respect to its other operand as the
	 * diagonal of that operand's values.
	 */
	@Test(timeout = 60000)
	public void productDeltaWithShapedOperand() {
		PackedCollection x = pack(1.0, 2.0, 3.0);
		CollectionProducer other = cp(pack(4.0, 5.0, 6.0));

		ShapedProducer shaped = new ShapedProducer(x);
		Assert.assertFalse(shaped instanceof Process);
		CollectionProductComputation product = new CollectionProductComputation(shape(3), shaped, other);

		CollectionProducer delta = product.delta(other);
		Assert.assertEquals(shape(3, 3), delta.getShape());
		assertValues(delta.evaluate(),
				1.0, 0.0, 0.0,
				0.0, 2.0, 0.0,
				0.0, 0.0, 3.0);
	}

	/**
	 * An input of a computation that is not a {@link Process} is still one of its inputs for
	 * matching: the computation contains it, is not independent of it, and finds it as its
	 * single matching input. The computation remains independent of an unrelated value.
	 */
	@Test(timeout = 60000)
	public void nonProcessInputsAreMatched() {
		PackedCollection x = pack(1.0, 2.0, 3.0);
		Producer<PackedCollection> raw = () -> args -> x;
		Assert.assertFalse(raw instanceof Process);

		CollectionProducer other = cp(pack(4.0, 5.0, 6.0));
		CollectionAddComputation sum = new CollectionAddComputation(shape(3), raw, other);

		Assert.assertTrue(sum.containsMatch(raw));
		Assert.assertFalse(sum.cannotMatch(raw));
		Assert.assertSame(raw, sum.matchInput(raw).orElseThrow());

		Producer<PackedCollection> unrelated = p(pack(7.0, 8.0, 9.0));
		Assert.assertFalse(sum.containsMatch(unrelated));
		Assert.assertTrue(sum.cannotMatch(unrelated));
	}

	/**
	 * {@link Producer#containsMatch(Producer)} stops at the first input that contains the
	 * target: a later input is never searched once an earlier one has matched, and is
	 * searched exactly once when no earlier one does.
	 */
	@Test(timeout = 60000)
	public void containsMatchStopsAtFirstMatchingInput() {
		Producer<PackedCollection> a = p(pack(1.0, 2.0, 3.0, 4.0));
		Producer<PackedCollection> b = p(pack(5.0, 6.0, 7.0, 8.0));
		int[] searches = new int[1];
		Producer<PackedCollection> counted = new Producer<>() {
			@Override
			public Evaluable<PackedCollection> get() {
				return args -> pack(0.0, 0.0, 0.0, 0.0);
			}

			@Override
			public boolean containsMatch(Producer<?> target) {
				searches[0]++;
				return false;
			}
		};

		Function<PackedCollection[], Function<Object[], PackedCollection>> function =
				inputs -> args -> inputs[0];
		DynamicCollectionProducer dynamic =
				new DynamicCollectionProducer(shape(4), function, false, true, a, counted);

		Assert.assertTrue(dynamic.containsMatch(a));
		Assert.assertEquals(0, searches[0]);

		Assert.assertFalse(dynamic.containsMatch(b));
		Assert.assertEquals(1, searches[0]);
	}

	/**
	 * The delta of a producer with respect to itself is the identity, not the zeros
	 * of an independent producer, also for a producer that is neither a value source nor
	 * has any inputs; with respect to an unrelated value it is zeros.
	 */
	@Test(timeout = 60000)
	public void directDeltaWithRespectToItself() {
		CollectionProducer scaled = cp(pack(1.0, 2.0, 3.0)).multiply(2.0);
		assertValues(scaled.delta(scaled).evaluate(),
				1.0, 0.0, 0.0,
				0.0, 1.0, 0.0,
				0.0, 0.0, 1.0);
		assertValues(scaled.delta(cp(pack(4.0, 5.0))).evaluate(),
				0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

		PackedCollection x = pack(1.0, 2.0);
		Function<Object[], PackedCollection> function = args -> x;
		DynamicCollectionProducer dynamic = new DynamicCollectionProducer(shape(2), function);
		assertValues(dynamic.delta(dynamic).evaluate(),
				1.0, 0.0,
				0.0, 1.0);
	}

	/**
	 * The Jacobian of a sum is shaped as the sum's shape followed by the target's shape:
	 * zeros of that shape for a target in none of the terms, the identity for a target that
	 * is one term, and twice the identity for a target that is both.
	 */
	@Test(timeout = 60000)
	public void sumJacobian() {
		CollectionProducer a = cp(pack(1.0, 2.0, 3.0));
		CollectionProducer sum = a.add(cp(pack(4.0, 5.0, 6.0)));

		CollectionProducer independent = sum.delta(cp(pack(7.0, 8.0)));
		Assert.assertEquals(shape(3, 2), independent.getShape());
		assertValues(independent.evaluate(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

		CollectionProducer term = sum.delta(a);
		Assert.assertEquals(shape(3, 3), term.getShape());
		assertValues(term.evaluate(),
				1.0, 0.0, 0.0,
				0.0, 1.0, 0.0,
				0.0, 0.0, 1.0);

		assertValues(a.add(a).delta(a).evaluate(),
				2.0, 0.0, 0.0,
				0.0, 2.0, 0.0,
				0.0, 0.0, 2.0);
	}

	/**
	 * The Jacobian of a product with respect to one factor is the diagonal of the other
	 * factor, and with respect to a factor that is both operands it is the diagonal of
	 * twice that factor.
	 */
	@Test(timeout = 60000)
	public void productJacobian() {
		CollectionProducer a = cp(pack(1.0, 2.0, 3.0));
		CollectionProducer product = a.multiply(cp(pack(4.0, 5.0, 6.0)));

		CollectionProducer diagonal = product.delta(a);
		Assert.assertEquals(shape(3, 3), diagonal.getShape());
		assertValues(diagonal.evaluate(),
				4.0, 0.0, 0.0,
				0.0, 5.0, 0.0,
				0.0, 0.0, 6.0);

		assertValues(a.multiply(a).delta(a).evaluate(),
				2.0, 0.0, 0.0,
				0.0, 4.0, 0.0,
				0.0, 0.0, 6.0);
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

	/**
	 * A producer of a fixed collection that declares its shape but is neither a
	 * {@link CollectionProducer} nor a {@link Process}.
	 */
	private static class ShapedProducer implements Producer<PackedCollection>,
			Shape<Producer<PackedCollection>> {
		/** The collection this producer produces. */
		private final PackedCollection value;

		/**
		 * Creates a producer of the given collection.
		 *
		 * @param value  the collection to produce
		 */
		ShapedProducer(PackedCollection value) {
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
			throw new UnsupportedOperationException();
		}

		@Override
		public Producer<PackedCollection> traverse(int axis) {
			throw new UnsupportedOperationException();
		}
	}
}
