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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Verifies the local rules of the {@link ReduceAxis reduced} delta,
 * {@link CollectionProducer#delta(Producer, ReduceAxis, Producer)}, for the computations
 * that provide one (element-wise product, sum, repeat), the general cases every producer
 * handles (a target it is, or cannot depend on), {@link ReduceAxis} itself, and
 * {@link CollectionProducerComputationBase#replaceInput(Producer, Producer)}. Every
 * reduced delta is checked against values computed on the host from its definition.
 *
 * @author Michael Murray
 */
public class ReducedDeltaTest extends TestSuiteBase {

	/** {@link ReduceAxis#of(int)} inverts {@link ReduceAxis#getIndex()} and rejects other indices. */
	@Test(timeout = 30000)
	public void reduceAxisIndices() {
		Assert.assertEquals(0, ReduceAxis.OUT.getIndex());
		Assert.assertEquals(1, ReduceAxis.IN.getIndex());
		Assert.assertSame(ReduceAxis.OUT, ReduceAxis.of(0));
		Assert.assertSame(ReduceAxis.IN, ReduceAxis.of(1));

		for (int index : new int[] { -1, 2 }) {
			try {
				ReduceAxis.of(index);
				Assert.fail("Expected an IllegalArgumentException for axis " + index);
			} catch (IllegalArgumentException expected) {
				Assert.assertTrue(expected.getMessage().contains(String.valueOf(index)));
			}
		}
	}

	/**
	 * The output-reduced delta keeps the target's shape and is weighted in the output's
	 * shape; the input-reduced delta is the reverse.
	 */
	@Test(timeout = 30000)
	public void reduceAxisShapes() {
		TraversalPolicy output = shape(3, 5);
		TraversalPolicy target = shape(7);

		Assert.assertSame(target, ReduceAxis.OUT.resultShape(output, target));
		Assert.assertSame(output, ReduceAxis.OUT.weightShape(output, target));
		Assert.assertSame(output, ReduceAxis.IN.resultShape(output, target));
		Assert.assertSame(target, ReduceAxis.IN.weightShape(output, target));
	}

	/**
	 * {@code replaceInput} swaps exactly the named input, and refuses a producer that is
	 * not one of the computation's inputs rather than returning the computation unchanged.
	 */
	@Test(timeout = 60000)
	public void replaceInput() {
		PackedCollection a = new PackedCollection(shape(4)).randnFill();
		PackedCollection b = new PackedCollection(shape(4)).randnFill();
		PackedCollection c = new PackedCollection(shape(4)).randnFill();

		Producer<PackedCollection> pa = cp(a);
		Producer<PackedCollection> pb = cp(b);
		CollectionProducer product = multiply(pa, pb);
		Assert.assertTrue(product instanceof CollectionProducerComputationBase);
		CollectionProducerComputationBase base = (CollectionProducerComputationBase) product;

		Producer<PackedCollection> second = base.getInputs().get(2);
		PackedCollection replaced = base.replaceInput(second, cp(c)).evaluate();
		double[] expected = new double[4];
		for (int i = 0; i < 4; i++) expected[i] = a.toDouble(i) * c.toDouble(i);
		assertValuesMatch(expected, replaced);

		try {
			base.replaceInput(cp(c), cp(a));
			Assert.fail("Expected an IllegalArgumentException for a producer that is not an input");
		} catch (IllegalArgumentException rejected) {
			Assert.assertTrue(rejected.getMessage().contains("is not an input"));
		}
	}

	/**
	 * A target the producer cannot depend on gives zeros in the shape of the remaining
	 * axis, and the target itself gives its weight (or ones without one), since its
	 * Jacobian is the identity.
	 */
	@Test(timeout = 60000)
	public void directCases() {
		PackedCollection x = new PackedCollection(shape(2, 3)).randnFill();
		PackedCollection other = new PackedCollection(shape(5)).randnFill();
		PackedCollection g = new PackedCollection(shape(2, 3)).randnFill();

		CollectionProducer f = cp(x).multiply(2.0);
		Producer<PackedCollection> independent = p(other);

		PackedCollection out = f.delta(independent, ReduceAxis.OUT, cp(g)).evaluate();
		Assert.assertEquals(5, out.getShape().getTotalSize());
		assertValuesMatch(new double[5], out);

		PackedCollection in = f.delta(independent, ReduceAxis.IN, cp(other)).evaluate();
		Assert.assertEquals(6, in.getShape().getTotalSize());
		assertValuesMatch(new double[6], in);

		CollectionProducer self = cp(x);
		assertValuesMatch(g.toArray(0, 6), self.delta(self, ReduceAxis.OUT, cp(g)).evaluate());
		assertValuesMatch(new double[] { 1, 1, 1, 1, 1, 1 }, self.delta(self, ReduceAxis.IN).evaluate());
	}

	/**
	 * The product rule for both axes: {@code d(a⊙b)/da} weighted by {@code g} is
	 * {@code g⊙b}, and applied to a tangent {@code t} it is {@code t⊙b}. When the target
	 * appears in both operands ({@code a⊙a}) each operand contributes, giving
	 * {@code 2a⊙g}.
	 */
	@Test(timeout = 5 * 60000)
	public void productRule() {
		int n = 6;
		PackedCollection a = new PackedCollection(shape(n)).randnFill();
		PackedCollection b = new PackedCollection(shape(n)).randnFill();
		PackedCollection g = new PackedCollection(shape(n)).randnFill();
		PackedCollection t = new PackedCollection(shape(n)).randnFill();

		Producer<PackedCollection> pa = p(a);
		CollectionProducer product = multiply(pa, p(b));
		CollectionProducer square = multiply(pa, pa);

		double[] weighted = new double[n];
		double[] tangent = new double[n];
		double[] doubled = new double[n];
		for (int i = 0; i < n; i++) {
			weighted[i] = g.toDouble(i) * b.toDouble(i);
			tangent[i] = t.toDouble(i) * b.toDouble(i);
			doubled[i] = 2 * a.toDouble(i) * g.toDouble(i);
		}

		assertValuesMatch(weighted, product.delta(pa, ReduceAxis.OUT, cp(g)).evaluate());
		assertValuesMatch(b.toArray(0, n), product.delta(pa, ReduceAxis.OUT).evaluate());
		assertValuesMatch(tangent, product.delta(pa, ReduceAxis.IN, cp(t)).evaluate());
		assertValuesMatch(doubled, square.delta(pa, ReduceAxis.OUT, cp(g)).evaluate());
	}

	/**
	 * The sum of each group of an input gives every element of the group the weight of the
	 * group's output, and applied to a tangent it sums the tangent over each group.
	 */
	@Test(timeout = 5 * 60000)
	public void sumRule() {
		int groups = 3;
		int size = 4;
		PackedCollection x = new PackedCollection(shape(groups, size)).randnFill();
		PackedCollection g = new PackedCollection(shape(groups)).randnFill();
		PackedCollection t = new PackedCollection(shape(groups, size)).randnFill();

		Producer<PackedCollection> px = p(x);
		CollectionSumComputation sum = new CollectionSumComputation(c(px).traverse(1));
		Assert.assertEquals(groups, sum.getShape().getTotalSize());

		double[] spread = new double[groups * size];
		double[] summed = new double[groups];
		for (int i = 0; i < groups; i++) {
			for (int j = 0; j < size; j++) {
				spread[i * size + j] = g.toDouble(i);
				summed[i] += t.toDouble(i * size + j);
			}
		}

		assertValuesMatch(spread, sum.delta(px, ReduceAxis.OUT, cp(g)).evaluate());
		assertValuesMatch(new double[] { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 },
				sum.delta(px, ReduceAxis.OUT).evaluate());
		assertValuesMatch(summed, sum.delta(px, ReduceAxis.IN, cp(t)).evaluate());
	}

	/**
	 * A repetition of each group of an input sums the weights of the copies of every
	 * element (or counts them, without a weight), and applied to a tangent it repeats the
	 * tangent.
	 */
	@Test(timeout = 5 * 60000)
	public void repeatRule() {
		int groups = 2;
		int copies = 3;
		int size = 4;
		PackedCollection x = new PackedCollection(shape(groups, size)).randnFill();
		PackedCollection g = new PackedCollection(shape(groups, copies, size)).randnFill();
		PackedCollection t = new PackedCollection(shape(groups, size)).randnFill();

		Producer<PackedCollection> px = p(x);
		PackedCollectionRepeat repeat = new PackedCollectionRepeat(copies, c(px).traverse(1));
		Assert.assertEquals(groups * copies * size, repeat.getShape().getTotalSize());

		double[] summed = new double[groups * size];
		double[] repeated = new double[groups * copies * size];
		for (int i = 0; i < groups; i++) {
			for (int c = 0; c < copies; c++) {
				for (int j = 0; j < size; j++) {
					summed[i * size + j] += g.toDouble((i * copies + c) * size + j);
					repeated[(i * copies + c) * size + j] = t.toDouble(i * size + j);
				}
			}
		}

		double[] counted = new double[groups * size];
		Arrays.fill(counted, copies);

		assertValuesMatch(summed, repeat.delta(px, ReduceAxis.OUT, cp(g)).evaluate());
		assertValuesMatch(counted, repeat.delta(px, ReduceAxis.OUT).evaluate());
		assertValuesMatch(repeated, repeat.delta(px, ReduceAxis.IN, cp(t)).evaluate());
	}

	/**
	 * A reshape presents its producer's reduced delta in its own shape: the output-reduced
	 * delta through a reshape agrees with the one through the producer it wraps, and the
	 * input-reduced delta takes the reshape's shape.
	 */
	@Test(timeout = 5 * 60000)
	public void reshapePassesThrough() {
		int n = 6;
		PackedCollection a = new PackedCollection(shape(n)).randnFill();
		PackedCollection b = new PackedCollection(shape(n)).randnFill();
		PackedCollection g = new PackedCollection(shape(2, 3)).randnFill();
		PackedCollection t = new PackedCollection(shape(n)).randnFill();

		Producer<PackedCollection> pa = p(a);
		CollectionProducer reshaped = multiply(pa, p(b)).reshape(shape(2, 3));

		double[] weighted = new double[n];
		double[] tangent = new double[n];
		for (int i = 0; i < n; i++) {
			weighted[i] = g.toDouble(i) * b.toDouble(i);
			tangent[i] = t.toDouble(i) * b.toDouble(i);
		}

		assertValuesMatch(weighted, reshaped.delta(pa, ReduceAxis.OUT, cp(g)).evaluate());

		CollectionProducer jvp = reshaped.delta(pa, ReduceAxis.IN, cp(t));
		Assert.assertEquals(2, jvp.getShape().getDimensions());
		assertValuesMatch(tangent, jvp.evaluate());
	}

	/**
	 * The product rule pairs elements of equal-sized operands, so it does not depend on the
	 * count being fixed: an element-wise product with a variable-count operand, which the
	 * full Jacobian cannot be compiled for, still has its output-reduced delta. Weighted by
	 * ones, {@code d(a⊙s)/da} is {@code s}.
	 */
	@Test(timeout = 60000)
	public void productRuleWithVariableCount() {
		PackedCollection a = new PackedCollection(shape(4)).randnFill();
		Producer<PackedCollection> pa = p(a);
		CollectionProducer product = multiply(pa,
				new ArithmeticSequenceComputation(shape(4), false, 1.0));
		Assert.assertFalse(product.isFixedCount());

		PackedCollection sequence = new ArithmeticSequenceComputation(shape(4), true, 1.0).evaluate();
		assertValuesMatch(sequence.toArray(0, 4), product.delta(pa, ReduceAxis.OUT).evaluate());
	}

	/**
	 * Asserts that the collection holds the expected values in order.
	 *
	 * @param expected the expected values
	 * @param actual   the collection to check
	 */
	private void assertValuesMatch(double[] expected, PackedCollection actual) {
		Assert.assertEquals(expected.length, actual.getShape().getTotalSize());
		double[] values = actual.toArray(0, expected.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals("index " + i, expected[i], values[i]);
		}
	}
}
