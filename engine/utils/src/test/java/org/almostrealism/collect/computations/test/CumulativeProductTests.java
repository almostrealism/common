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

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ConstantRepeatedDeltaComputation;
import org.almostrealism.collect.computations.ConstantRepeatedProducerComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that {@code cumulativeProduct} matches a host-computed reference for both the
 * inclusive and the padded (exclusive) forms, and that {@link Tensor#pack()} preserves
 * element values and ordering.
 */
public class CumulativeProductTests extends TestSuiteBase {

	/**
	 * Tests the inclusive cumulative product against a host-computed reference,
	 * where element i is the product of input elements 0 through i.
	 */
	@Test(timeout = 60000)
	public void cumulativeProductInclusive() {
		int n = 25;

		PackedCollection input = new PackedCollection(shape(n));
		rand(input.getShape()).add(0.5).into(input.traverseEach()).evaluate();

		PackedCollection result = cumulativeProduct(cp(input), false).get().evaluate();
		Assert.assertEquals(n, result.getShape().getTotalSize());

		double r = 1.0;
		for (int i = 0; i < n; i++) {
			r *= input.valueAt(i);
			assertEquals(r, result.valueAt(i));
		}
	}

	/**
	 * Tests the padded (exclusive) cumulative product against a host-computed reference,
	 * where element 0 is 1.0 and element i is the product of input elements 0 through i - 1.
	 */
	@Test(timeout = 60000)
	public void cumulativeProductExclusive() {
		int n = 25;

		PackedCollection input = new PackedCollection(shape(n));
		rand(input.getShape()).add(0.5).into(input.traverseEach()).evaluate();

		PackedCollection result = cumulativeProduct(cp(input), true).get().evaluate();
		Assert.assertEquals(n, result.getShape().getTotalSize());

		double r = 1.0;
		for (int i = 0; i < n; i++) {
			assertEquals(r, result.valueAt(i));
			r *= input.valueAt(i);
		}
	}

	/**
	 * Tests the inclusive cumulative product for fixed input values, pinning the exact output of
	 * the scan's accumulation loop to independently computed constants. Each iteration reads the
	 * running product back through the shared accumulator reference on
	 * {@code RepeatedProducerComputation}, so a fixed input isolates that read from the randomness
	 * of {@link #cumulativeProductInclusive()}.
	 */
	@Test(timeout = 60000)
	public void cumulativeProductFixedValues() {
		PackedCollection input = pack(2.0, 3.0, 0.5, 4.0, 1.5);
		PackedCollection result = cumulativeProduct(cp(input), false).get().evaluate();

		double[] expected = { 2.0, 6.0, 3.0, 12.0, 18.0 };
		Assert.assertEquals(expected.length, result.getShape().getTotalSize());
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], result.valueAt(i));
		}
	}

	/**
	 * Differentiates an inclusive cumulative product with respect to its input and pins the shape
	 * and values of the resulting Jacobian.
	 *
	 * <p>Differentiating a prefix scan routes through {@code ConstantRepeatedDeltaComputation},
	 * which re-evaluates the scan's loop body with a {@code null} global index. That is the only
	 * path that reaches the null branch of {@code RepeatedProducerComputation.kernelIndex} and the
	 * delta-side call of {@code getCurrentOutputValue} (which must read the delta kernel's own
	 * output argument, {@code args[0]}, not {@code getOutputVariable()}); a forward evaluation
	 * never exercises either. This test therefore guards against a regression that made that path
	 * throw a {@link NullPointerException} when the index-normalization helper stopped tolerating a
	 * {@code null} global index.
	 *
	 * <p>The Jacobian itself is a {@code (5, 5)} collection of zeros: the running product is read
	 * back through an output-accumulator memory reference, which differentiation treats as a
	 * constant, so the in-loop term contributes no gradient. That result is a known limitation of
	 * prefix-scan differentiation — not a property this test endorses as the mathematically correct
	 * Jacobian — but it is the established, non-throwing behavior the consolidation must preserve.
	 */
	@Test(timeout = 120000)
	public void cumulativeProductDelta() {
		PackedCollection input = pack(2.0, 3.0, 0.5, 4.0, 1.5);

		PackedCollection jacobian = cumulativeProduct(cp(input), false)
				.delta(cp(input)).evaluate();

		Assert.assertEquals(2, jacobian.getShape().getDimensions());
		Assert.assertEquals(5, jacobian.getShape().length(0));
		Assert.assertEquals(5, jacobian.getShape().length(1));
		Assert.assertEquals(25, jacobian.getMemLength());

		for (int i = 0; i < 25; i++) {
			assertEquals(0.0, jacobian.toDouble(i));
		}
	}

	/**
	 * Compiles the cumulative-product Jacobian and asserts that every element its delta kernel
	 * writes lies inside the {@code (5, 5)} output, then checks the evaluated values.
	 *
	 * <p>Each kernel of {@link ConstantRepeatedDeltaComputation} owns one row of the Jacobian. If
	 * the destination of a write counts that row's offset twice, the later kernels write past the
	 * end of the output. Native allocators may tolerate such writes silently (only some platforms
	 * abort on the resulting heap corruption), so this test bounds the destination indices of the
	 * compiled kernel directly, which detects the overrun deterministically on every backend.</p>
	 */
	@Test(timeout = 120000)
	public void cumulativeProductDeltaStaysWithinDestination() {
		int n = 5;
		PackedCollection input = pack(2.0, 3.0, 0.5, 4.0, 1.5);

		DestinationBoundDelta delta = new DestinationBoundDelta(
				(ConstantRepeatedProducerComputation) cumulativeProduct(cp(input), false), cp(input));
		PackedCollection jacobian = delta.get().evaluate();

		Assert.assertEquals(n * n, jacobian.getMemLength());
		Assert.assertEquals(n * n, delta.getDestinationsChecked());
		Assert.assertEquals(0, delta.getMinDestination());
		Assert.assertEquals(n * n - 1, delta.getMaxDestination());

		for (int i = 0; i < n * n; i++) {
			assertEquals(0.0, jacobian.toDouble(i));
		}
	}

	/**
	 * A {@link ConstantRepeatedDeltaComputation} that, when its scope is compiled, records the
	 * smallest lower bound and largest upper bound of the output index of every destination its
	 * kernels write: one per kernel, iteration and element of the iteration's memory block. The
	 * index is the reference's position before it is reduced modulo the output length, which is
	 * the element the kernel actually addresses.
	 */
	private static class DestinationBoundDelta extends ConstantRepeatedDeltaComputation {
		/** The smallest lower bound of any destination index, or {@link Long#MAX_VALUE} before compilation. */
		private long minDestination = Long.MAX_VALUE;

		/** The largest upper bound of any destination index, or {@link Long#MIN_VALUE} before compilation. */
		private long maxDestination = Long.MIN_VALUE;

		/** The number of destinations the most recent compilation wrote, summed over kernels. */
		private long destinationsChecked;

		/**
		 * Creates the delta of the given computation with respect to the given target.
		 *
		 * @param source the computation to differentiate
		 * @param target the producer with respect to which the derivative is computed
		 */
		DestinationBoundDelta(ConstantRepeatedProducerComputation source, Producer<?> target) {
			super(source, target);
		}

		/**
		 * Compiles the scope, then records the bounds of the destination of every write the loop
		 * body makes, for every kernel the context allows. The scope may be compiled more than once,
		 * so the bounds accumulate across compilations while the count describes the latest one.
		 *
		 * @param context the kernel structure context of the compilation
		 * @return the compiled scope
		 */
		@Override
		public Scope<PackedCollection> getScope(KernelStructureContext context) {
			Scope<PackedCollection> scope = super.getScope(context);
			long kernels = context.getKernelMaximum().orElseThrow();
			destinationsChecked = 0;

			for (int i = 0; i < count; i++) {
				for (int j = 0; j < getMemLength(); j++) {
					Expression<?> index = getDestination(new KernelIndex(context), e(i), e(j))
							.getChildren().get(0);
					minDestination = Math.min(minDestination, index.lowerBound(context).orElseThrow());
					maxDestination = Math.max(maxDestination, index.upperBound(context).orElseThrow());
					destinationsChecked += kernels;
				}
			}

			return scope;
		}

		/** @return the smallest lower bound of any destination index */
		long getMinDestination() { return minDestination; }

		/** @return the largest upper bound of any destination index */
		long getMaxDestination() { return maxDestination; }

		/** @return the number of destinations the most recent compilation wrote, summed over kernels */
		long getDestinationsChecked() { return destinationsChecked; }
	}

	/**
	 * Tests that packing a numeric {@link Tensor} produces a collection with the
	 * tensor's shape and its element values in position order.
	 */
	@Test(timeout = 60000)
	public void tensorPack() {
		Tensor<Double> t = new Tensor<>();
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 4; j++) {
				t.insert(10.0 * i + j, i, j);
			}
		}

		PackedCollection c = t.pack();
		Assert.assertEquals(12, c.getShape().getTotalSize());

		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 4; j++) {
				assertEquals(10.0 * i + j, c.valueAt(i, j));
			}
		}
	}
}
