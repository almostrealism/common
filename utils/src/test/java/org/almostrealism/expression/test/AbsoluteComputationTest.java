/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.expression.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Tests for {@link Absolute} expression when used in a computation context.
 * This tests the full pipeline from expression tree to compiled code execution.
 */
public class AbsoluteComputationTest extends TestSuiteBase {

	/**
	 * A minimal computation that computes: output[i] = 1.0 - |input[i] - 1.0|
	 * This is similar to what Bartlett window does.
	 */
	static class AbsoluteTestComputation extends CollectionProducerComputationBase {
		private final int size;

		public AbsoluteTestComputation(int size) {
			super("absoluteTest", new TraversalPolicy(size).traverse());
			this.size = size;
		}

		@Override
		public Scope<PackedCollection> getScope(KernelStructureContext context) {
			Scope<PackedCollection> scope = super.getScope(context);

			Expression<?> n = kernel(context);
			Expression<?> nMinus1 = e((double) (size - 1));

			// Compute: 2*n/(size-1) - 1.0, then |x|, then 1.0 - |x|
			// This mimics the Bartlett window formula
			Expression<Double> normalized = (Expression<Double>) e(2.0).multiply(n).divide(nMinus1).subtract(e(1.0));
			Expression<Double> absValue = new Absolute(normalized);
			Expression<?> result = e(1.0).subtract(absValue);

			// Debug: print what we're assigning
			System.out.println("AbsoluteTestComputation - result expression type: " + result.getClass().getName());
			System.out.println("AbsoluteTestComputation - result children: " + result.getChildren().size());
			for (int i = 0; i < result.getChildren().size(); i++) {
				System.out.println("  Child " + i + ": " + result.getChildren().get(i).getClass().getName());
			}

			scope.getStatements().add(
					getCollectionArgumentVariable(0).getValueAt(n).assign(result)
			);

			return scope;
		}

		@Override
		public AbsoluteTestComputation generate(List<Process<?, ?>> children) {
			return new AbsoluteTestComputation(size);
		}
	}

	/**
	 * Test that Absolute works correctly in a computation context.
	 * Expected values for size=64:
	 * - n=0: 1 - |0 - 1| = 0
	 * - n=1: 1 - |2/63 - 1| = 1 - 0.968 = 0.032
	 * - n=31: 1 - |62/63 - 1| = 1 - 0.016 = 0.984
	 * - n=32: 1 - |64/63 - 1| = 1 - 0.016 = 0.984
	 * - n=63: 1 - |2 - 1| = 0
	 */
	@Test
	public void testAbsoluteInComputation() {
		int size = 64;
		AbsoluteTestComputation comp = new AbsoluteTestComputation(size);
		PackedCollection result = comp.get().evaluate();

		// Expected values using reference formula
		double[] expected = new double[size];
		for (int n = 0; n < size; n++) {
			double normalized = 2.0 * n / (size - 1) - 1.0;
			expected[n] = 1.0 - Math.abs(normalized);
		}

		System.out.println("Checking computation results:");
		System.out.println("  result[0] = " + result.toDouble(0) + " (expected " + expected[0] + ")");
		System.out.println("  result[1] = " + result.toDouble(1) + " (expected " + expected[1] + ")");
		System.out.println("  result[31] = " + result.toDouble(31) + " (expected " + expected[31] + ")");
		System.out.println("  result[32] = " + result.toDouble(32) + " (expected " + expected[32] + ")");
		System.out.println("  result[63] = " + result.toDouble(63) + " (expected " + expected[63] + ")");

		// Check specific indices
		assertEquals("Index 0", expected[0], result.toDouble(0), 1e-10);
		assertEquals("Index 1", expected[1], result.toDouble(1), 1e-10);
		assertEquals("Index 31", expected[31], result.toDouble(31), 1e-10);
		assertEquals("Index 32", expected[32], result.toDouble(32), 1e-10);
		assertEquals("Index 63", expected[63], result.toDouble(63), 1e-10);

		// Check all values
		for (int i = 0; i < size; i++) {
			assertEquals("Index " + i, expected[i], result.toDouble(i), 1e-10);
		}
	}

	/**
	 * Simpler test: just compute |x| directly without subtraction.
	 */
	static class SimpleAbsoluteComputation extends CollectionProducerComputationBase {
		private final int size;

		public SimpleAbsoluteComputation(int size) {
			super("simpleAbsolute", new TraversalPolicy(size).traverse());
			this.size = size;
		}

		@Override
		public Scope<PackedCollection> getScope(KernelStructureContext context) {
			Scope<PackedCollection> scope = super.getScope(context);

			Expression<?> n = kernel(context);
			Expression<?> nMinus1 = e((double) (size - 1));

			// Compute: |2*n/(size-1) - 1.0|
			Expression<Double> normalized = (Expression<Double>) e(2.0).multiply(n).divide(nMinus1).subtract(e(1.0));
			Expression<Double> absValue = new Absolute(normalized);

			System.out.println("SimpleAbsoluteComputation - absValue type: " + absValue.getClass().getName());
			System.out.println("SimpleAbsoluteComputation - absValue children: " + absValue.getChildren().size());

			scope.getStatements().add(
					getCollectionArgumentVariable(0).getValueAt(n).assign(absValue)
			);

			return scope;
		}

		@Override
		public SimpleAbsoluteComputation generate(List<Process<?, ?>> children) {
			return new SimpleAbsoluteComputation(size);
		}
	}

	/**
	 * Test Absolute directly (without outer subtraction).
	 */
	@Test
	public void testSimpleAbsoluteComputation() {
		int size = 64;
		SimpleAbsoluteComputation comp = new SimpleAbsoluteComputation(size);
		PackedCollection result = comp.get().evaluate();

		// Expected values: |2*n/(size-1) - 1.0|
		double[] expected = new double[size];
		for (int n = 0; n < size; n++) {
			expected[n] = Math.abs(2.0 * n / (size - 1) - 1.0);
		}

		System.out.println("Checking simple absolute results:");
		System.out.println("  result[0] = " + result.toDouble(0) + " (expected " + expected[0] + ")");
		System.out.println("  result[1] = " + result.toDouble(1) + " (expected " + expected[1] + ")");
		System.out.println("  result[31] = " + result.toDouble(31) + " (expected " + expected[31] + ")");

		for (int i = 0; i < size; i++) {
			assertEquals("Index " + i, expected[i], result.toDouble(i), 1e-10);
		}
	}
}
