/*
 * Copyright 2026 Michael Murray
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
	 * Verifies that declarations inside a nested inner loop are NOT hoisted
	 * out of the outer loop when they reference the inner loop's index variable.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {
	 *     double f_outer_inv = 42.0;          // invariant w.r.t. outer loop
	 *     for (j = 0; j < 10; j++) {
	 *       { double f_inner_var = j; }       // variant — references inner index j
	 *     }
	 *   }
	 * }
	 * </pre>
	 *
	 * <p>Only {@code f_outer_inv} should be hoisted. {@code f_inner_var} references
	 * the inner loop index {@code j} and must remain inside the inner loop.</p>
	 */
	@Test(timeout = 30000)
	public void nestedInnerLoopDeclarationNotHoisted() {
		Variable<Integer, ?> outerIdx = Variable.integer("_test_i");
		Repeated<Void> outerLoop = new Repeated<>(outerIdx, outerIdx.ref().lessThan(100));
		outerLoop.setName("nestedTest");

		Scope<Void> outerBody = new Scope<>("outerBody");

		// Invariant declaration (does not reference any loop index)
		outerBody.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_outer_inv"),
				new DoubleConstant(42.0)));

		// Create inner loop
		Variable<Integer, ?> innerIdx = Variable.integer("_test_j");
		Repeated<Void> innerLoop = new Repeated<>(innerIdx, innerIdx.ref().lessThan(10));
		innerLoop.setName("innerLoop");

		Scope<Void> innerBody = new Scope<>("innerBody");
		// Declaration that references the inner loop index via StaticReference
		innerBody.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_inner_var"),
				new StaticReference<>(Double.class, "_test_j")));
		innerLoop.getChildren().add(innerBody);
		outerBody.getChildren().add(innerLoop);

		outerLoop.getChildren().add(outerBody);

		Repeated<Void> simplified = (Repeated<Void>) outerLoop.simplify(new NoOpKernelStructureContext(), 0);

		// f_outer_inv should be hoisted to the outer loop's statements
		Assert.assertTrue("f_outer_inv should be hoisted out of the outer loop",
				countDeclarationsNamed(simplified.getStatements(), "f_outer_inv") > 0);

		// f_inner_var should NOT be hoisted — it depends on the inner loop index
		Assert.assertEquals("f_inner_var should NOT be hoisted out of the outer loop",
				0, countDeclarationsNamed(simplified.getStatements(), "f_inner_var"));
	}

	/**
	 * Verifies that a declaration whose expression references a variable that is
	 * assigned (via non-declaration assignment) elsewhere in the loop body is
	 * NOT hoisted. The non-declaration assignment makes the target variable
	 * loop-variant, and any declaration depending on it must also stay in the loop.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {
	 *     target[0] = i;                       // non-declaration assignment (mutates target)
	 *     double f_depends = target;           // variant — depends on loop-mutated target
	 *     double f_independent = 42.0;         // invariant — no dependencies on loop vars
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void declarationDependingOnAssignedVariableNotHoisted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("assignedVarTest");

		Scope<Void> child = new Scope<>("body");

		// Non-declaration assignment: target = i (mutates target each iteration)
		child.getStatements().add(new ExpressionAssignment<>(
				false,
				new StaticReference<>(Double.class, "target"),
				new StaticReference<>(Double.class, "_test_i")));

		// Declaration that references the assigned variable "target"
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_depends"),
				new StaticReference<>(Double.class, "target")));

		// Independent invariant declaration
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_independent"),
				new DoubleConstant(42.0)));

		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// f_depends should NOT be hoisted because it references "target" which is assigned in the loop
		Assert.assertEquals("f_depends should NOT be hoisted (depends on loop-assigned target)",
				0, countDeclarationsNamed(simplified.getStatements(), "f_depends"));

		// f_independent should be hoisted
		Assert.assertTrue("f_independent should be hoisted",
				countDeclarationsNamed(simplified.getStatements(), "f_independent") > 0);
	}

	/**
	 * Verifies that Phase 4 sub-expression extraction finds and hoists
	 * invariant sub-expressions within a variant expression.
	 *
	 * <p>This test uses {@code pow()} (Exponent) to build a binary invariant
	 * sub-expression that won't be flattened by the N-ary simplification
	 * (unlike Product/Sum which are N-ary and get flattened).</p>
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {
	 *     // pow(genome, 3.0) is invariant — genome is a kernel argument
	 *     // pow(genome, 3.0) * i is variant — multiplied by loop index
	 *     double f_result = pow(genome, 3.0) * i;
	 *   }
	 * }
	 * </pre>
	 *
	 * <p>After LICM Phase 4, the invariant sub-expression {@code pow(genome, 3.0)}
	 * should be extracted into a new {@code f_licm_*} declaration and hoisted
	 * before the loop.</p>
	 */
	@Test(timeout = 30000)
	public void invariantSubExpressionExtracted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("subExprTest");

		Scope<Void> child = new Scope<>("body");

		// Build a variant expression that contains an invariant sub-expression:
		// result = pow(genome, 3.0) * i
		// pow(genome, 3.0) is a binary Exponent expression (not N-ary, won't flatten)
		Expression<Double> genomeRef = new StaticReference<>(Double.class, "genome_val");
		Expression<?> invariantSubExpr = genomeRef.pow(new DoubleConstant(3.0));
		Expression<?> variantExpr = invariantSubExpr.multiply(new StaticReference<>(Double.class, "_test_i"));

		child.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_result"),
				variantExpr));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// The extracted sub-expression should appear as a f_licm_* declaration
		// in the Repeated scope's hoisted statements (before the loop)
		long licmDeclarations = countDeclarationsWithPrefix(simplified.getStatements(), "f_licm_");

		Assert.assertTrue("Invariant sub-expression should be extracted and hoisted as f_licm_* declaration",
				licmDeclarations > 0);
	}

	/**
	 * Verifies that sub-expression extraction does NOT extract trivial
	 * sub-expressions like constants and simple references. Only substantial
	 * sub-expressions (with tree depth > 1) should be extracted.
	 */
	@Test(timeout = 30000)
	public void trivialSubExpressionsNotExtracted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("trivialSubExprTest");

		Scope<Void> child = new Scope<>("body");

		// Build a variant expression whose invariant parts are all trivial:
		// result = 42.0 * i
		// 42.0 is a constant — should NOT be extracted into a separate declaration
		Expression<?> variantExpr = new DoubleConstant(42.0)
				.multiply(new StaticReference<>(Double.class, "_test_i"));

		child.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_trivial"),
				variantExpr));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// No f_licm_* declarations should be created for trivial sub-expressions
		long licmDeclarations = countDeclarationsWithPrefix(simplified.getStatements(), "f_licm_");

		Assert.assertEquals("Trivial sub-expressions should NOT be extracted", 0, licmDeclarations);
	}

	/**
	 * Verifies that sub-expression extraction correctly handles the AudioScene
	 * pattern: a variant expression containing a deeply nested invariant sub-tree
	 * (simulating genome-only {@code pow()} sub-expressions within envelope
	 * accumulate expressions).
	 *
	 * <p>This mirrors the real-world AudioScene code where envelope accumulate
	 * expressions contain invariant {@code pow()} sub-expressions that read
	 * from genome parameters at constant offsets, multiplied by a loop-variant
	 * frame counter.</p>
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 4096; i++) {
	 *   {
	 *     // pow((-pow(genome, 3.0)) + 1.0, -1.0) is invariant (genome-only)
	 *     // The whole expression is variant (multiplied by frame counter i)
	 *     double f_accum = pow((-pow(genome, 3.0)) + 1.0, -1.0) * i;
	 *   }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void genomeSubExpressionPattern() {
		Variable<Integer, ?> idx = Variable.integer("_frame_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(4096));
		loop.setName("genomePatternTest");

		Scope<Void> child = new Scope<>("body");

		// Build genome-only sub-expression matching the real AudioScene pattern:
		// pow((-pow(genome, 3.0)) + 1.0, -1.0)
		Expression<Double> genomeRef = new StaticReference<>(Double.class, "genome_param");
		Expression<?> innerPow = genomeRef.pow(new DoubleConstant(3.0));
		Expression<?> negated = innerPow.minus();
		Expression<?> plusOne = negated.add(new DoubleConstant(1.0));
		Expression<?> outerPow = ((Expression<Double>) plusOne).pow(new DoubleConstant(-1.0));

		// Build variant expression: outerPow * frameCounter
		Expression<?> variantAccum = outerPow.multiply(
				new StaticReference<>(Double.class, "_frame_i"));

		child.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_accum"),
				variantAccum));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// Verify a f_licm_* declaration was extracted for the genome sub-expression
		long licmDeclarations = countDeclarationsWithPrefix(simplified.getStatements(), "f_licm_");

		Assert.assertTrue("Genome-only sub-expression should be extracted",
				licmDeclarations > 0);

		// The variant expression should remain in the child scope but reference
		// the extracted declaration instead of the original sub-expression
		long remaining = countDeclarationsInDescendants(simplified, "f_accum");
		Assert.assertTrue("Variant expression f_accum should remain in loop body",
				remaining > 0);
	}

	/**
	 * Verifies that duplicate invariant sub-expressions are deduplicated
	 * during extraction — a single declaration is created and referenced
	 * in multiple places.
	 *
	 * <p>Uses {@code pow()} (Exponent) to create a binary invariant sub-expression
	 * that won't be flattened by N-ary simplification.</p>
	 */
	@Test(timeout = 30000)
	public void duplicateSubExpressionsDeduped() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("dedupTest");

		Scope<Void> child = new Scope<>("body");

		// Same invariant sub-expression (pow) used in two variant expressions
		Expression<Double> genomeRef = new StaticReference<>(Double.class, "genome_val");
		Expression<?> invariantSub = genomeRef.pow(new DoubleConstant(3.0));

		// f_a = pow(genome, 3.0) * i
		child.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_a"),
				invariantSub.multiply(new StaticReference<>(Double.class, "_test_i"))));

		// f_b = pow(genome, 3.0) * (i + 1)
		Expression<?> iPlusOne = new StaticReference<Double>(Double.class, "_test_i")
				.add(new DoubleConstant(1.0));
		child.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_b"),
				invariantSub.multiply(iPlusOne)));

		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// Count f_licm_* declarations — should be deduplicated
		long licmDeclarations = countDeclarationsWithPrefix(simplified.getStatements(), "f_licm_");

		// If dedup works, there should be exactly 1 declaration for the shared sub-expression
		// (could be more if the expression system creates structurally different objects,
		//  but should not be more than 2 at most)
		Assert.assertTrue("At least one f_licm_* declaration should be created",
				licmDeclarations >= 1);
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
	@Test(timeout = 60000) @TestDepth(2)
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

		double[] withoutLicm = runWeightedSumWithLicm(false, "noLicm",
				outputShape, outerCount, innerCount, inputShape, weightShape,
				inputIndexer, weightIndexer, input, weights, outputSize);

		double[] withLicm = runWeightedSumWithLicm(true, "withLicm",
				outputShape, outerCount, innerCount, inputShape, weightShape,
				inputIndexer, weightIndexer, input, weights, outputSize);

		// Compare
		for (int k = 0; k < outputSize; k++) {
			Assert.assertEquals(
					"Output[" + k + "] differs between LICM-enabled and LICM-disabled",
					withoutLicm[k], withLicm[k], 1e-6);
		}
	}

	/**
	 * Verifies that Phase 4 sub-expression extraction works for non-declaration
	 * assignments (e.g., array element writes like {@code source[offset] = expr}).
	 *
	 * <p>Non-declaration assignments use a higher extraction threshold
	 * ({@code treeDepth >= 3}) to avoid extracting trivially cheap sub-expressions
	 * that would cause code blowup. Only deep, computationally expensive invariant
	 * sub-expressions like nested genome pow() chains should be extracted.</p>
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {
	 *     // Non-declaration: array write with deep invariant sub-expression
	 *     output[i] = pow(pow(genome, 3.0) + 1.0, -1.0) * frameValue
	 *   }
	 * }
	 * </pre>
	 *
	 * <p>The sub-expression {@code pow(pow(genome, 3.0) + 1.0, -1.0)} has
	 * treeDepth >= 3 and should be extracted, while simpler invariant
	 * sub-expressions should be left in place.</p>
	 */
	@Test(timeout = 30000)
	public void nonDeclarationAssignmentSubExpressionExtracted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("nonDeclTest");

		Scope<Void> child = new Scope<>("body");

		// Build a deep invariant sub-expression (treeDepth >= 3):
		// pow(pow(genome, 3.0) + 1.0, -1.0)
		Expression<Double> genomeRef = new StaticReference<>(Double.class, "genome_val");
		Expression<?> innerPow = genomeRef.pow(new DoubleConstant(3.0));  // depth 1
		Expression<?> sum = innerPow.add(new DoubleConstant(1.0));         // depth 2
		Expression<?> outerPow = sum.pow(new DoubleConstant(-1.0));        // depth 3

		// Non-declaration: output[i] = deepInvariant * frameValue
		Expression<?> variantExpr = outerPow.multiply(
				new StaticReference<>(Double.class, "_test_i"));

		child.getStatements().add(new ExpressionAssignment(
				false,
				new StaticReference<>(Double.class, "output"),
				variantExpr));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// The deep invariant sub-expression should be extracted as f_licm_*
		long licmDeclarations = countDeclarationsWithPrefix(simplified.getStatements(), "f_licm_");

		Assert.assertTrue("Deep invariant sub-expression in non-declaration should be extracted",
				licmDeclarations > 0);
	}

	/**
	 * Verifies that shallow invariant sub-expressions in non-declaration
	 * assignments are NOT extracted (they use the higher treeDepth >= 3 threshold).
	 *
	 * <p>A simple {@code pow(genome, 3.0)} has treeDepth=1, which is below
	 * the non-declaration threshold of 3. It should be left in place.</p>
	 */
	@Test(timeout = 30000)
	public void shallowSubExpressionsNotExtractedFromNonDeclaration() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("shallowNonDeclTest");

		Scope<Void> child = new Scope<>("body");

		// Shallow invariant sub-expression (treeDepth = 1):
		// pow(genome, 3.0) — below the non-declaration threshold of 3
		Expression<Double> genomeRef = new StaticReference<>(Double.class, "genome_val");
		Expression<?> shallowInvariant = genomeRef.pow(new DoubleConstant(3.0));

		// Non-declaration: output[i] = shallowInvariant * i
		Expression<?> variantExpr = shallowInvariant.multiply(
				new StaticReference<>(Double.class, "_test_i"));

		child.getStatements().add(new ExpressionAssignment(
				false,
				new StaticReference<>(Double.class, "output"),
				variantExpr));
		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// No f_licm_* should be created — the sub-expression is too shallow
		long licmDeclarations = countDeclarationsWithPrefix(simplified.getStatements(), "f_licm_");

		Assert.assertEquals("Shallow invariant sub-expression in non-declaration should NOT be extracted",
				0, licmDeclarations);
	}

	/**
	 * Verifies that a declaration inside a helper function scope (simulating
	 * {@code timeSeriesValueAt}) that uses its own inner loop index is NOT hoisted
	 * out of the outer loop. The helper scope's loop index is different from the
	 * outer loop's, but LICM must still recognize it as variant w.r.t. the inner
	 * loop and not hoist it to before the outer loop.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 4096; i++) {
	 *   {
	 *     // Helper function with its own loop
	 *     for (j = 0; j < 32; j++) {
	 *       { double f_helper_result = j * 2.0; }  // variant w.r.t. inner loop j
	 *     }
	 *     // Invariant declaration (no loop references)
	 *     double f_outer_const = 42.0;
	 *   }
	 * }
	 * </pre>
	 *
	 * <p>Only {@code f_outer_const} should be hoisted. {@code f_helper_result}
	 * references the inner loop index {@code j} and must remain inside the inner loop,
	 * which itself must stay inside the outer loop.</p>
	 */
	@Test(timeout = 30000)
	public void helperFunctionWithOwnLoopIndexNotHoisted() {
		Variable<Integer, ?> outerIdx = Variable.integer("_outer_i");
		Repeated<Void> outerLoop = new Repeated<>(outerIdx, outerIdx.ref().lessThan(4096));
		outerLoop.setName("outerLoop");

		Scope<Void> outerBody = new Scope<>("outerBody");

		// Inner helper loop (simulating timeSeriesValueAt)
		Variable<Integer, ?> innerIdx = Variable.integer("_helper_j");
		Repeated<Void> innerLoop = new Repeated<>(innerIdx, innerIdx.ref().lessThan(32));
		innerLoop.setName("helperLoop");

		Scope<Void> innerBody = new Scope<>("innerBody");
		// Declaration that references the inner loop index
		Expression<?> innerExpr = new StaticReference<>(Double.class, "_helper_j")
				.multiply(new DoubleConstant(2.0));
		innerBody.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_helper_result"),
				innerExpr));
		innerLoop.getChildren().add(innerBody);
		outerBody.getChildren().add(innerLoop);

		// Invariant declaration outside helper
		outerBody.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_outer_const"),
				new DoubleConstant(42.0)));

		outerLoop.getChildren().add(outerBody);

		Repeated<Void> simplified = (Repeated<Void>) outerLoop.simplify(new NoOpKernelStructureContext(), 0);

		// f_outer_const should be hoisted
		Assert.assertTrue("f_outer_const should be hoisted out of the outer loop",
				countDeclarationsNamed(simplified.getStatements(), "f_outer_const") > 0);

		// f_helper_result should NOT be hoisted out of the outer loop
		Assert.assertEquals("f_helper_result should NOT be hoisted out of the outer loop",
				0, countDeclarationsNamed(simplified.getStatements(), "f_helper_result"));
	}

	/**
	 * Verifies that variance propagation correctly handles a chain where a
	 * StaticReference to a variant declaration makes a subsequent declaration
	 * variant as well. This is critical for the AudioScene pipeline where
	 * envelope expressions reference {@code f_assignment} declarations that
	 * reference the frame counter.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 100; i++) {
	 *   {
	 *     double f_time_dep = i * 2.0;           // variant (references i directly)
	 *     double f_indirect = f_time_dep + 1.0;  // variant (references f_time_dep via StaticRef)
	 *     double f_const = 99.0;                 // invariant
	 *   }
	 * }
	 * </pre>
	 *
	 * <p>Only {@code f_const} should be hoisted. Both {@code f_time_dep} and
	 * {@code f_indirect} must remain in the loop.</p>
	 */
	@Test(timeout = 30000)
	public void staticReferenceVariancePropagation() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(100));
		loop.setName("staticRefPropTest");

		Scope<Void> child = new Scope<>("body");

		// f_time_dep = i * 2.0 (directly variant)
		child.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_time_dep"),
				new StaticReference<Double>(Double.class, "_test_i")
						.multiply(new DoubleConstant(2.0))));

		// f_indirect = f_time_dep + 1.0 (indirectly variant via StaticReference)
		child.getStatements().add(new ExpressionAssignment(
				true,
				new StaticReference<>(Double.class, "f_indirect"),
				new StaticReference<Double>(Double.class, "f_time_dep")
						.add(new DoubleConstant(1.0))));

		// f_const = 99.0 (invariant)
		child.getStatements().add(new ExpressionAssignment<>(
				true,
				new StaticReference<>(Double.class, "f_const"),
				new DoubleConstant(99.0)));

		loop.getChildren().add(child);

		Repeated<Void> simplified = (Repeated<Void>) loop.simplify(new NoOpKernelStructureContext(), 0);

		// f_time_dep should NOT be hoisted (directly variant)
		Assert.assertEquals("f_time_dep should NOT be hoisted",
				0, countDeclarationsNamed(simplified.getStatements(), "f_time_dep"));

		// f_indirect should NOT be hoisted (transitively variant via StaticReference)
		Assert.assertEquals("f_indirect should NOT be hoisted (depends on variant f_time_dep)",
				0, countDeclarationsNamed(simplified.getStatements(), "f_indirect"));

		// f_const should be hoisted
		Assert.assertTrue("f_const should be hoisted",
				countDeclarationsNamed(simplified.getStatements(), "f_const") > 0);
	}

	/**
	 * Runs a {@link LoopedWeightedSumComputation} with the specified LICM setting
	 * and returns the output values. Used by differential tests to compare
	 * LICM-enabled vs LICM-disabled results.
	 *
	 * @param enableLicm whether to enable loop-invariant code motion
	 * @param label name prefix for the computation and result assignment
	 * @param outputShape output shape
	 * @param outerCount outer loop count
	 * @param innerCount inner loop count
	 * @param inputShape input shape
	 * @param weightShape weight shape
	 * @param inputIndexer input index function
	 * @param weightIndexer weight index function
	 * @param input input data
	 * @param weights weight data
	 * @param outputSize number of output elements to extract
	 * @return the output values as a double array
	 */
	private double[] runWeightedSumWithLicm(boolean enableLicm, String label,
											TraversalPolicy outputShape, int outerCount, int innerCount,
											TraversalPolicy inputShape, TraversalPolicy weightShape,
											LoopedWeightedSumComputation.InputIndexer inputIndexer,
											LoopedWeightedSumComputation.WeightIndexer weightIndexer,
											PackedCollection input, PackedCollection weights,
											int outputSize) {
		boolean previous = Repeated.enableLoopInvariantHoisting;
		Repeated.enableLoopInvariantHoisting = enableLicm;
		try {
			LoopedWeightedSumComputation computation = new LoopedWeightedSumComputation(
					label,
					outputShape, outerCount, innerCount,
					inputShape, weightShape,
					inputIndexer, weightIndexer,
					cp(input), cp(weights));

			OperationList ops = new OperationList();
			PackedCollection output = new PackedCollection(outputShape);
			ops.add(a(label + "Result", p(output), computation));
			ops.get().run();

			double[] result = new double[outputSize];
			for (int k = 0; k < outputSize; k++) {
				result[k] = output.toDouble(k);
			}
			return result;
		} finally {
			Repeated.enableLoopInvariantHoisting = previous;
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
	 * Counts how many declaration statements in the given list have a destination
	 * name starting with the specified prefix (e.g., "f_licm_").
	 */
	private long countDeclarationsWithPrefix(List<Statement<?>> statements, String prefix) {
		return statements.stream()
				.filter(s -> s instanceof ExpressionAssignment)
				.map(s -> (ExpressionAssignment<?>) s)
				.filter(ExpressionAssignment::isDeclaration)
				.filter(a -> {
					Expression<?> dest = a.getDestination();
					return dest instanceof StaticReference
							&& ((StaticReference<?>) dest).getName() != null
							&& ((StaticReference<?>) dest).getName().startsWith(prefix);
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
