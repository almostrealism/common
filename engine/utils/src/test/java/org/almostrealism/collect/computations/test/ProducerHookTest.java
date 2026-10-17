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
import org.almostrealism.calculus.DeltaAlternate;
import org.almostrealism.calculus.MultiTermDeltaStrategy;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
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
 * features: {@link CollectionProducer#attemptMultiply(double)},
 * {@link CollectionProducer#isReduction()},
 * {@link io.almostrealism.collect.CollectionProducerBase#isDirectlyComputable()}, the
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

		Producer<PackedCollection> generic = sequence;
		CollectionProducer delegated = CollectionProducer.attemptMultiply(generic, -2.0);
		Assert.assertTrue(delegated instanceof ArithmeticSequenceComputation);
		assertValues(delegated.evaluate(), -2.0, -6.0, -10.0, -14.0, -18.0);

		CollectionProducer values = cp(pack(1.0, 2.0, 3.0));
		Assert.assertNull(values.attemptMultiply(3.0));
		Assert.assertNull(CollectionProducer.attemptMultiply(values, 3.0));
		Assert.assertNull(multiply(3.0, values));
		assertValues(multiply(c(3.0), values).evaluate(), 3.0, 6.0, 9.0);
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

		CollectionProducer shared = c(target).multiply(2.0);
		ReshapeProducer declaring = new ReshapeProducer(shape(2, 2), shared);
		ReshapeProducer other = new ReshapeProducer(shape(4, 1), shared);
		CollectionProducer alternate = c(target).multiply(3.0).reshape(shape(2, 2));
		declaring.withDeltaAlternate(alternate);

		assertValues(other.delta(target, ReduceAxis.OUT, gradient).evaluate(), 2.0, -2.0, 1.0, 4.0);
		Assert.assertNull(other.getDeltaAlternate());
		Assert.assertNull(shared.getDeltaAlternate());

		CollectionProducer delta = declaring.delta(target);
		Assert.assertEquals(List.of(2, 2, 4), List.of(delta.getShape().length(0),
				delta.getShape().length(1), delta.getShape().length(2)));
		assertTripledIdentity(delta);
		assertTripledIdentity(shared.delta(target).multiply(1.5));

		assertValues(declaring.delta(target, ReduceAxis.OUT, gradient).evaluate(), 3.0, -3.0, 1.5, 6.0);

		CollectionProducer flattened = declaring.reshape(shape(4));
		assertValues(flattened.delta(target, ReduceAxis.OUT, gradient).evaluate(), 3.0, -3.0, 1.5, 6.0);
		Assert.assertEquals(shape(4), flattened.getDeltaAlternate().getShape());
		assertTripledIdentity(flattened.delta(target));

		CollectionProducer traversed = declaring.traverse(1);
		assertValues(traversed.delta(target, ReduceAxis.OUT, gradient).evaluate(), 3.0, -3.0, 1.5, 6.0);
		assertTripledIdentity(traversed.delta(target));

		ReshapeProducer generated = declaring.generate(List.of((Process<?, ?>) shared));
		assertValues(generated.delta(target, ReduceAxis.OUT, gradient).evaluate(), 3.0, -3.0, 1.5, 6.0);
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
		Assert.assertNull(dynamic.attemptDirectDelta(a));

		Assert.assertTrue(dynamic.matchingInputs(b).isEmpty());
		Assert.assertFalse(dynamic.containsMatch(b));
		Assert.assertTrue(dynamic.cannotMatch(b));
		assertValues(dynamic.attemptDirectDelta(b).evaluate(), new double[16]);

		assertValues(dynamic.evaluate(), 2.0, 4.0, 6.0, 8.0);
	}

	/**
	 * The terms of a computation are its inputs, including one that is not a
	 * {@link Process} (which its process children would only present wrapped): such a term
	 * is counted, and excluding it leaves exactly the other term.
	 */
	@Test(timeout = 60000)
	public void termsIncludeNonProcessInputs() {
		PackedCollection x = pack(1.0, 2.0, 3.0);
		Producer<PackedCollection> raw = () -> args -> x;
		Assert.assertFalse(raw instanceof Process);

		CollectionProducer other = cp(pack(4.0, 5.0, 6.0));
		CollectionAddComputation sum = new CollectionAddComputation(shape(3), raw, other);

		Assert.assertEquals(1, sum.countTerms(raw));
		Assert.assertEquals(1, sum.countTerms(other));
		Assert.assertEquals(0, sum.countTerms(cp(pack(7.0, 8.0, 9.0))));
		Assert.assertSame(other, sum.excludeTerm(raw));
	}

	/**
	 * Excluding one term of a three-term computation rebuilds it from the remaining two
	 * when both are {@link Process}es. When one of the remaining terms is not a
	 * {@link Process} the computation cannot be rebuilt: the exclusion returns null rather
	 * than failing, and {@link MultiTermDeltaStrategy#COMBINE} declines for that reason.
	 */
	@Test(timeout = 60000)
	public void excludeTermRebuildsOnlyFromProcesses() {
		CollectionProducer first = cp(pack(1.0, 2.0, 3.0));
		CollectionProducer other = cp(pack(4.0, 5.0, 6.0));
		CollectionProducer excluded = cp(pack(7.0, 8.0, 9.0));

		CollectionProducer remaining =
				new CollectionAddComputation(shape(3), first, other, excluded).excludeTerm(excluded);
		Assert.assertTrue(remaining instanceof CollectionProducerComputation);
		Assert.assertEquals(1, remaining.matchingInputs(first).size());
		Assert.assertEquals(1, remaining.matchingInputs(other).size());
		Assert.assertTrue(remaining.matchingInputs(excluded).isEmpty());
		assertValues(remaining.evaluate(), 5.0, 7.0, 9.0);

		PackedCollection x = pack(1.0, 2.0, 3.0);
		Producer<PackedCollection> raw = () -> args -> x;
		Assert.assertNull(new CollectionAddComputation(shape(3), raw, other, excluded)
				.excludeTerm(excluded));

		CollectionProductComputation product =
				new CollectionProductComputation(shape(3), raw, other, excluded);
		Assert.assertNull(MultiTermDeltaStrategy.COMBINE.delta(product, excluded));
	}

	/**
	 * Excluding one term of a two-term computation leaves a single term. A term that is
	 * neither a {@link CollectionProducer} nor a {@link Shape} has
	 * no shape to present it by, so the exclusion returns null and
	 * {@link MultiTermDeltaStrategy#COMBINE} declines rather than failing with a
	 * {@link ClassCastException}. A term that has a shape is presented through a
	 * {@link ReshapeProducer}, and the product's delta is the diagonal of its values.
	 */
	@Test(timeout = 60000)
	public void excludeTermPresentsSingleRemainingTerm() {
		PackedCollection x = pack(1.0, 2.0, 3.0);
		CollectionProducer other = cp(pack(4.0, 5.0, 6.0));

		Producer<PackedCollection> raw = () -> args -> x;
		CollectionProductComputation rawProduct = new CollectionProductComputation(shape(3), raw, other);
		Assert.assertNull(rawProduct.excludeTerm(other));
		Assert.assertNull(MultiTermDeltaStrategy.COMBINE.delta(rawProduct, other));
		Assert.assertSame(other, rawProduct.excludeTerm(raw));

		ShapedProducer shaped = new ShapedProducer(x);
		Assert.assertFalse(shaped instanceof Process);
		CollectionProductComputation shapedProduct =
				new CollectionProductComputation(shape(3), shaped, other);

		CollectionProducer remaining = shapedProduct.excludeTerm(other);
		Assert.assertEquals(3, remaining.getShape().getTotalSize());
		Assert.assertTrue(remaining.containsMatch(shaped));
		assertValues(remaining.evaluate(), 1.0, 2.0, 3.0);

		CollectionProducer delta = MultiTermDeltaStrategy.COMBINE.delta(shapedProduct, other);
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
		Assert.assertNull(sum.attemptDirectDelta(raw));

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
