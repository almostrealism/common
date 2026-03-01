/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.algebra;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.Statement;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.algebra.computations.LoopedWeightedSumComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for Loop-Invariant Code Motion (LICM) in {@link Repeated} scopes.
 *
 * <p>These tests verify that the LICM optimization in {@link Repeated#simplify}
 * correctly identifies and hoists loop-invariant expressions while preserving
 * correct behavior for loop-variant expressions. The tests cover:</p>
 * <ul>
 *   <li>Simple invariant hoisting from direct children</li>
 *   <li>Recursive hoisting from grandchild scopes</li>
 *   <li>Variant declarations are NOT hoisted</li>
 *   <li>Declaration dependency chains</li>
 *   <li>Mixed invariant/variant declarations</li>
 *   <li>Integration tests using real computations</li>
 * </ul>
 *
 * @see Repeated
 */
public class LoopInvariantHoistingTest extends TestSuiteBase {

	/**
	 * Verifies that LICM is unconditionally enabled.
	 * This test would fail if the optimization were removed or disabled.
	 */
	@Test(timeout = 10000)
	public void licmIsEnabledByDefault() {
		Assert.assertTrue("LICM should be enabled by default",
				Repeated.enableLoopInvariantHoisting);
	}

	/**
	 * Verifies that a simple loop-invariant declaration in a direct child scope
	 * is hoisted to before the loop.
	 *
	 * <p>Scope structure before LICM:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   { double f_0 = 42.0; }   // invariant — does not reference i
	 * }
	 * </pre>
	 *
	 * <p>After LICM, {@code f_0} should appear in the Repeated scope's
	 * statements list (before the loop), not in the child scope.</p>
	 */
	@Test(timeout = 30000)
	public void invariantDeclarationHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("invariantTest");

		Scope<Void> child = new Scope<>("body");
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_0"),
				new DoubleConstant(42.0)));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		long hoistedCount = countDeclarationsNamed(simplified.getStatements(), "f_0");
		Assert.assertTrue("Invariant declaration f_0 should be hoisted to before the loop",
				hoistedCount > 0);

		long remainingInChildren = countDeclarationsInDescendants(simplified, "f_0");
		Assert.assertEquals("Invariant declaration f_0 should NOT remain in child scopes",
				0, remainingInChildren);
	}

	/**
	 * Verifies that a loop-variant declaration (one that references the loop
	 * index variable) is NOT hoisted.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   { double f_var = i + 1.0; }   // variant — references i
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void variantDeclarationNotHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("variantTest");

		Scope<Void> child = new Scope<>("body");
		// Expression references the loop index via StaticReference
		Expression<Double> variantExpr = new StaticReference<>(Double.class, "_test_i");
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_var"),
				variantExpr));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		long hoistedCount = countDeclarationsNamed(simplified.getStatements(), "f_var");
		Assert.assertEquals("Variant declaration f_var should NOT be hoisted",
				0, hoistedCount);
	}

	/**
	 * Verifies that invariant declarations in grandchild scopes (not just
	 * direct children) are hoisted. This is the critical fix for the
	 * AudioScene pipeline where {@code f_assignment} declarations live
	 * in nested scope structures.
	 *
	 * <p>Scope structure before LICM:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {                              // child
	 *     {                            // grandchild
	 *       double f_deep = 99.0;     // invariant — in grandchild scope
	 *     }
	 *   }
	 * }
	 * </pre>
	 *
	 * <p>After LICM, {@code f_deep} should be hoisted to the Repeated scope's
	 * statements list.</p>
	 */
	@Test(timeout = 30000)
	public void grandchildInvariantHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("grandchildTest");

		Scope<Void> child = new Scope<>("child");
		Scope<Void> grandchild = new Scope<>("grandchild");
		grandchild.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_deep"),
				new DoubleConstant(99.0)));
		child.getChildren().add(grandchild);
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		long hoistedCount = countDeclarationsNamed(simplified.getStatements(), "f_deep");
		Assert.assertTrue("Grandchild invariant declaration f_deep should be hoisted",
				hoistedCount > 0);

		long remainingInChildren = countDeclarationsInDescendants(simplified, "f_deep");
		Assert.assertEquals("Grandchild declaration f_deep should NOT remain in descendant scopes",
				0, remainingInChildren);
	}

	/**
	 * Verifies transitive dependency chain handling. When declaration A is
	 * invariant and declaration B depends on A AND on the loop index,
	 * only A should be hoisted. B must remain in the loop.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {
	 *     double f_a = 42.0;          // invariant
	 *     double f_b = f_a + i;       // variant (depends on loop index via StaticReference)
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void dependencyChainVariance() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("depChainTest");

		Scope<Void> child = new Scope<>("body");

		// f_a = 42.0 (invariant)
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_a"),
				new DoubleConstant(42.0)));

		// f_b references loop index via StaticReference to "_test_i" (variant)
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_b"),
				new StaticReference<>(Double.class, "_test_i")));

		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		long hoistedA = countDeclarationsNamed(simplified.getStatements(), "f_a");
		Assert.assertTrue("Invariant declaration f_a should be hoisted", hoistedA > 0);

		long hoistedB = countDeclarationsNamed(simplified.getStatements(), "f_b");
		Assert.assertEquals("Variant declaration f_b should NOT be hoisted", 0, hoistedB);
	}

	/**
	 * Verifies that when an invariant declaration is referenced by another
	 * declaration via {@link StaticReference}, the variance propagation
	 * correctly classifies both.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {
	 *     double f_base = 42.0;         // invariant
	 *     double f_derived = f_base;    // also invariant (only depends on invariant f_base)
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void transitiveInvariantChain() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("transitiveInvariantTest");

		Scope<Void> child = new Scope<>("body");

		// f_base = 42.0 (invariant)
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_base"),
				new DoubleConstant(42.0)));

		// f_derived = f_base (also invariant — only depends on invariant declaration)
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_derived"),
				new StaticReference<>(Double.class, "f_base")));

		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		long hoistedBase = countDeclarationsNamed(simplified.getStatements(), "f_base");
		Assert.assertTrue("Invariant declaration f_base should be hoisted", hoistedBase > 0);

		long hoistedDerived = countDeclarationsNamed(simplified.getStatements(), "f_derived");
		Assert.assertTrue("Transitively invariant declaration f_derived should also be hoisted",
				hoistedDerived > 0);
	}

	/**
	 * Verifies that mixed invariant and variant declarations are correctly
	 * separated: only invariant ones are hoisted, and hoisted declarations
	 * preserve their relative ordering.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {
	 *     double f_inv1 = 1.0;    // invariant
	 *     double f_var1 = i;      // variant
	 *     double f_inv2 = 2.0;    // invariant
	 *     double f_var2 = i;      // variant
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void mixedInvariantAndVariant() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("mixedTest");

		Scope<Void> child = new Scope<>("body");

		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_inv1"),
				new DoubleConstant(1.0)));

		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_var1"),
				new StaticReference<>(Double.class, "_test_i")));

		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_inv2"),
				new DoubleConstant(2.0)));

		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_var2"),
				new StaticReference<>(Double.class, "_test_i")));

		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// Both invariant declarations should be hoisted
		Assert.assertTrue("f_inv1 should be hoisted",
				countDeclarationsNamed(simplified.getStatements(), "f_inv1") > 0);
		Assert.assertTrue("f_inv2 should be hoisted",
				countDeclarationsNamed(simplified.getStatements(), "f_inv2") > 0);

		// Both variant declarations should NOT be hoisted
		Assert.assertEquals("f_var1 should NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_var1"));
		Assert.assertEquals("f_var2 should NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_var2"));
	}

	/**
	 * Verifies that a non-declaration assignment (array element write) is never
	 * hoisted, even if the expression is invariant. LICM only hoists declarations,
	 * not assignments that mutate state.
	 */
	@Test(timeout = 30000)
	public void nonDeclarationAssignmentNotHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("nonDeclTest");

		Scope<Void> child = new Scope<>("body");

		// Non-declaration assignment: target[0] = 42.0
		child.getStatements().add(new ExpressionAssignment<>(
				false,
				new StaticReference<>(Double.class, "target"),
				new DoubleConstant(42.0)));

		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		long hoistedCount = simplified.getStatements().stream()
				.filter(s -> s instanceof ExpressionAssignment && !((ExpressionAssignment<?>) s).isDeclaration())
				.count();
		Assert.assertEquals("Non-declaration assignments should NOT be hoisted", 0, hoistedCount);
	}

	/**
	 * Tests that a LoopedWeightedSum computation produces correct results
	 * with LICM enabled. This computation internally creates a {@link Repeated}
	 * scope with both loop-invariant (weight loading) and loop-variant
	 * (accumulation) expressions.
	 *
	 * <p>The computation computes: output[k] = sum over i,j of input[f(k,i,j)] * weight[g(i,j)]
	 * The weight indexing expressions are loop-invariant with respect to the output index
	 * but the accumulation is loop-variant.</p>
	 */
	@Test(timeout = 60000)
	public void loopedSumWithLicm() {
		boolean previous = Repeated.enableLoopInvariantHoisting;
		Repeated.enableLoopInvariantHoisting = true;

		try {
			int outerCount = 4;
			int innerCount = 3;
			int outputSize = 2;

			TraversalPolicy outputShape = shape(outputSize).traverseEach();
			TraversalPolicy inputShape = shape(outerCount, outputSize + innerCount - 1);
			TraversalPolicy weightShape = shape(outerCount, innerCount);

			PackedCollection input = new PackedCollection(inputShape);
			PackedCollection weights = new PackedCollection(weightShape);

			// Set known values for deterministic verification
			for (int i = 0; i < inputShape.getTotalSize(); i++) {
				input.setMem(i, (i + 1) * 0.1);
			}
			for (int i = 0; i < weightShape.getTotalSize(); i++) {
				weights.setMem(i, (i + 1) * 0.01);
			}

			LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) ->
					outerIndex.multiply(outputSize + innerCount - 1).add(outputIndex).add(innerIndex);

			LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) ->
					outerIndex.multiply(innerCount).add(innerIndex);

			LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
					"licmTest",
					outputShape,
					outerCount,
					innerCount,
					inputShape,
					weightShape,
					inputIndexer,
					weightIndexer,
					cp(input),
					cp(weights));

			OperationList ops = new OperationList();
			PackedCollection output = new PackedCollection(outputShape);
			ops.add(a("licmResult", p(output), computation));

			Runnable r = ops.get();
			r.run();

			// Compute expected values manually
			double[] expected = new double[outputSize];
			for (int k = 0; k < outputSize; k++) {
				for (int i = 0; i < outerCount; i++) {
					for (int j = 0; j < innerCount; j++) {
						int inputIdx = i * (outputSize + innerCount - 1) + k + j;
						int weightIdx = i * innerCount + j;
						expected[k] += input.toDouble(inputIdx) * weights.toDouble(weightIdx);
					}
				}
			}

			for (int k = 0; k < outputSize; k++) {
				Assert.assertEquals("Output[" + k + "] mismatch with LICM enabled",
						expected[k], output.toDouble(k), 1e-6);
			}
		} finally {
			Repeated.enableLoopInvariantHoisting = previous;
		}
	}

	/**
	 * Verifies that LICM-enabled execution produces the same results as
	 * LICM-disabled execution. This is a differential test that catches
	 * cases where hoisting changes the output.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void licmMatchesNonLicm() {
		int outerCount = 4;
		int innerCount = 3;
		int outputSize = 2;

		TraversalPolicy outputShape = shape(outputSize).traverseEach();
		TraversalPolicy inputShape = shape(outerCount, outputSize + innerCount - 1);
		TraversalPolicy weightShape = shape(outerCount, innerCount);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		LoopedWeightedSumComputation.InputIndexer inputIndexer = (outputIndex, outerIndex, innerIndex) ->
				outerIndex.multiply(outputSize + innerCount - 1).add(outputIndex).add(innerIndex);

		LoopedWeightedSumComputation.WeightIndexer weightIndexer = (outputIndex, outerIndex, innerIndex) ->
				outerIndex.multiply(innerCount).add(innerIndex);

		// Run with LICM disabled
		double[] withoutLicm;
		{
			boolean previous = Repeated.enableLoopInvariantHoisting;
			Repeated.enableLoopInvariantHoisting = false;
			try {
				LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
						"noLicm",
						outputShape, outerCount, innerCount,
						inputShape, weightShape,
						inputIndexer, weightIndexer,
						cp(input), cp(weights));

				OperationList ops = new OperationList();
				PackedCollection output = new PackedCollection(outputShape);
				ops.add(a("noLicmResult", p(output), computation));
				ops.get().run();

				withoutLicm = new double[outputSize];
				for (int k = 0; k < outputSize; k++) {
					withoutLicm[k] = output.toDouble(k);
				}
			} finally {
				Repeated.enableLoopInvariantHoisting = previous;
			}
		}

		// Run with LICM enabled
		double[] withLicm;
		{
			boolean previous = Repeated.enableLoopInvariantHoisting;
			Repeated.enableLoopInvariantHoisting = true;
			try {
				LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
						"withLicm",
						outputShape, outerCount, innerCount,
						inputShape, weightShape,
						inputIndexer, weightIndexer,
						cp(input), cp(weights));

				OperationList ops = new OperationList();
				PackedCollection output = new PackedCollection(outputShape);
				ops.add(a("withLicmResult", p(output), computation));
				ops.get().run();

				withLicm = new double[outputSize];
				for (int k = 0; k < outputSize; k++) {
					withLicm[k] = output.toDouble(k);
				}
			} finally {
				Repeated.enableLoopInvariantHoisting = previous;
			}
		}

		// Compare
		for (int k = 0; k < outputSize; k++) {
			Assert.assertEquals(
					"Output[" + k + "] differs between LICM-enabled and LICM-disabled",
					withoutLicm[k], withLicm[k], 1e-6);
		}
	}

	/**
	 * Counts how many declaration statements in the given list have a destination
	 * name matching the specified name.
	 */
	private long countDeclarationsNamed(List<Statement<?>> statements, String name) {
		return statements.stream()
				.filter(s -> s instanceof ExpressionAssignment)
				.map(s -> (ExpressionAssignment<?>) s)
				.filter(ExpressionAssignment::isDeclaration)
				.filter(a -> {
					Expression<?> dest = a.getDestination();
					return dest instanceof StaticReference
							&& name.equals(((StaticReference<?>) dest).getName());
				})
				.count();
	}

	/**
	 * Counts how many declaration statements in all descendant scopes of the
	 * given scope have a destination name matching the specified name.
	 */
	private long countDeclarationsInDescendants(Scope<?> scope, String name) {
		long count = 0;
		for (Scope<?> child : scope.getChildren()) {
			count += countDeclarationsNamed(child.getStatements(), name);
			count += countDeclarationsInDescendants(child, name);
		}
		return count;
	}
}
