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
import io.almostrealism.code.Precision;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Cases;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Tests for accumulator promotion in {@link Repeated} scopes.
 *
 * <p>These tests verify that {@link Repeated#simplify} rewrites reduction-style
 * loop bodies (a store to the same array element on every iteration) into an
 * ordinary {@link Scope} that declares a local accumulator before the loop, uses
 * it inside the loop, and stores it back once afterwards, and that the transform
 * bails out whenever promotion could change the loop's semantics.</p>
 *
 * <p>When a loop is promoted, {@link Repeated#simplify} returns a plain
 * {@link Scope} — not a {@link Repeated} — whose statements are the accumulator
 * declarations, whose first child is the rewritten loop, and whose second child is
 * a {@link Scope} holding the final stores. When nothing qualifies, it returns the
 * {@link Repeated} unchanged.</p>
 *
 * @see Repeated
 */
public class AccumulatorPromotionTest extends TestSuiteBase {

	/**
	 * Verifies that accumulator promotion is unconditionally enabled.
	 * This test would fail if the optimization were removed or disabled.
	 */
	@Test(timeout = 10000)
	public void promotionIsEnabledByDefault() {
		Assert.assertTrue("Accumulator promotion should be enabled by default",
				ScopeSettings.enableAccumulatorPromotion);
	}

	/**
	 * Verifies that a reduction-style loop body is rewritten to accumulate in a
	 * local variable with a single post-loop store.
	 *
	 * <p>Scope structure before promotion:</p>
	 * <pre>
	 * for (i = 0; i < 64; i++) {
	 *   { out[_gid] = out[_gid] + in[i]; }
	 * }
	 * </pre>
	 *
	 * <p>After promotion the loop is wrapped in a {@link Scope} whose statements
	 * declare the accumulator (initialized from {@code out[_gid]}), whose first
	 * child is the loop accumulating into the local, and whose second child stores
	 * the accumulator back to {@code out[_gid]}.</p>
	 */
	@Test(timeout = 30000)
	public void simpleReductionPromoted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(64));
		loop.setName("reductionTest");

		ArrayVariable<Double> out = new ArrayVariable(Double.class, "out", e(4));
		ArrayVariable<Double> in = new ArrayVariable(Double.class, "in", e(64));

		Scope<Void> body = new Scope<>("body");
		body.getStatements().add(new ExpressionAssignment(
				outElement(out), outElement(out).add(inElement(in))));
		loop.getChildren().add(body);

		Scope<Void> simplified = loop.simplify(new NoOpKernelStructureContext(), 0);

		// Promotion wraps the loop in a plain Scope rather than returning a Repeated
		Assert.assertFalse("A promoted loop should be wrapped in a plain Scope",
				simplified instanceof Repeated);

		// A declaration for the accumulator appears before the loop
		Assert.assertEquals("An accumulator declaration should be added before the loop",
				1, countAccumulatorDeclarations(simplified.getStatements()));

		// The wrapper holds the loop followed by the store scope
		Assert.assertEquals("The wrapper should hold the loop and the store scope",
				2, simplified.getChildren().size());

		Scope<Void> storedLoop = simplified.getChildren().get(0);
		Scope<Void> stores = simplified.getChildren().get(1);

		// The store scope stores the accumulator back to the array element
		Assert.assertEquals("The store scope should contain the final store",
				1, stores.getStatements().size());
		ExpressionAssignment<?> store = (ExpressionAssignment<?>) stores.getStatements().get(0);
		Assert.assertTrue("The final store destination should reference the array",
				store.getDestination().containsReference(out));

		// The loop body no longer references the output array at all
		Assert.assertFalse("The loop body should no longer reference the promoted array",
				bodyReferencesArray(storedLoop, out));

		// The input array is still read inside the loop
		Assert.assertTrue("The loop body should still read the input array",
				bodyReferencesArray(storedLoop, in));
	}

	/**
	 * Verifies that a store whose element position depends on the loop index is
	 * NOT promoted, since each iteration writes a different element.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 64; i++) {
	 *   { out[i] = out[i] + 1.0; }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void variantPositionNotPromoted() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(64));
		loop.setName("variantPositionTest");

		ArrayVariable<Double> out = new ArrayVariable(Double.class, "out", e(64));
		Expression<Integer> pos = new StaticReference<>(Integer.class, "_test_i");
		Expression<Double> element = new InstanceReference(out, pos, pos);

		Scope<Void> body = new Scope<>("body");
		body.getStatements().add(new ExpressionAssignment(element, element.add(e(1.0))));
		loop.getChildren().add(body);

		Scope<Void> simplified = loop.simplify(new NoOpKernelStructureContext(), 0);

		Assert.assertTrue("A loop with a variant position should not be promoted",
				simplified instanceof Repeated);
		Assert.assertTrue("The loop body should still store to the array",
				bodyReferencesArray(simplified, out));
	}

	/**
	 * Verifies that promotion is abandoned when the array is also read at a
	 * position other than the promoted element, since that read could alias
	 * the promoted element on some iteration.
	 *
	 * <p>Scope structure:</p>
	 * <pre>
	 * for (i = 0; i < 64; i++) {
	 *   { out[_gid] = out[_gid] + out[i]; }
	 * }
	 * </pre>
	 */
	@Test(timeout = 30000)
	public void mismatchedReferenceBlocksPromotion() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(64));
		loop.setName("mismatchedRefTest");

		ArrayVariable<Double> out = new ArrayVariable(Double.class, "out", e(64));

		Scope<Void> body = new Scope<>("body");
		body.getStatements().add(new ExpressionAssignment(
				outElement(out), outElement(out).add(inElement(out))));
		loop.getChildren().add(body);

		Scope<Void> simplified = loop.simplify(new NoOpKernelStructureContext(), 0);

		Assert.assertTrue("Promotion should be abandoned when the array is read elsewhere",
				simplified instanceof Repeated);
		Assert.assertTrue("The loop body should still store to the array",
				bodyReferencesArray(simplified, out));
	}

	/**
	 * Verifies that promotion is abandoned when the loop body contains a
	 * {@link Cases} scope, since its branch conditions read memory outside the
	 * statement list. This mirrors the Periodic-inside-Loop structure, where a
	 * counter element is incremented by a statement but tested by a branch
	 * condition: promoting the counter to a register would leave the condition
	 * reading a stale global value.
	 */
	@Test(timeout = 30000)
	public void casesScopeBlocksPromotion() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(64));
		loop.setName("casesTest");

		ArrayVariable<Double> counter = new ArrayVariable(Double.class, "counter", e(1));
		ArrayVariable<Double> out = new ArrayVariable(Double.class, "out", e(1));

		Scope<Void> body = new Scope<>("body");
		body.getStatements().add(new ExpressionAssignment(
				outElement(counter), outElement(counter).add(e(1.0))));

		Cases<Void> cases = new Cases<>("check");
		Scope<Void> branch = new Scope<>("branch");
		branch.getStatements().add(new ExpressionAssignment(
				outElement(out), outElement(out).add(e(1.0))));
		cases.addCase(outElement(counter).greaterThanOrEqual(e(5.0)), branch, null);
		body.getChildren().add(cases);

		loop.getChildren().add(body);

		Scope<Void> simplified = loop.simplify(new NoOpKernelStructureContext(), 0);

		Assert.assertTrue("Promotion should be abandoned when the body contains a Cases scope",
				simplified instanceof Repeated);
		Assert.assertTrue("The counter increment should still store to the array",
				bodyReferencesArray(simplified, counter));
		Assert.assertTrue("The branch should still store to the array",
				bodyReferencesArray(simplified, out));
	}

	/**
	 * Verifies that promotion is abandoned when the loop condition reads the
	 * array being stored to, since the condition would then observe stale values
	 * once the stores are deferred until after the loop.
	 */
	@Test(timeout = 30000)
	public void conditionReferenceBlocksPromotion() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		ArrayVariable<Double> out = new ArrayVariable(Double.class, "out", e(4));

		Expression<Boolean> condition = idx.ref().lessThan(64)
				.and(outElement(out).greaterThan(e(0.0)));
		Repeated<Void> loop = new Repeated<>(idx, condition);
		loop.setName("conditionRefTest");

		ArrayVariable<Double> in = new ArrayVariable(Double.class, "in", e(64));

		Scope<Void> body = new Scope<>("body");
		body.getStatements().add(new ExpressionAssignment(
				outElement(out), outElement(out).add(inElement(in))));
		loop.getChildren().add(body);

		Scope<Void> simplified = loop.simplify(new NoOpKernelStructureContext(), 0);

		Assert.assertTrue("Promotion should be abandoned when the condition reads the array",
				simplified instanceof Repeated);
		Assert.assertTrue("The loop body should still store to the array",
				bodyReferencesArray(simplified, out));
	}

	/**
	 * Verifies that the generated code places the accumulator declaration before
	 * the loop, uses the accumulator inside the loop, and stores it back to the
	 * array element after the loop closes.
	 */
	@Test(timeout = 30000)
	public void storeRenderedAfterLoop() {
		Variable<Integer, ?> idx = Variable.integer("_test_i");
		Repeated<Void> loop = new Repeated<>(idx, idx.ref().lessThan(64));
		loop.setName("renderTest");

		ArrayVariable<Double> out = new ArrayVariable(Double.class, "out", e(4));
		ArrayVariable<Double> in = new ArrayVariable(Double.class, "in", e(64));

		Scope<Void> body = new Scope<>("body");
		body.getStatements().add(new ExpressionAssignment(
				outElement(out), outElement(out).add(inElement(in))));
		loop.getChildren().add(body);

		Scope<Void> simplified = loop.simplify(new NoOpKernelStructureContext(), 0);

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		CPrintWriter writer = new CPrintWriter(buffer, "renderTest", Precision.FP64);
		simplified.write(writer);
		writer.flush();
		String code = buffer.toString();

		int declaration = code.indexOf("_test_i_acc0 = out[");
		int loopStart = code.indexOf("for (int _test_i");
		int loopEnd = code.indexOf("}", loopStart);
		int store = code.indexOf("out[", loopEnd);

		Assert.assertTrue("The accumulator declaration should be rendered", declaration >= 0);
		Assert.assertTrue("The loop should be rendered", loopStart >= 0 && loopEnd > loopStart);
		Assert.assertTrue("The declaration should precede the loop", declaration < loopStart);
		Assert.assertTrue("The final store should follow the loop", store > loopEnd);
		Assert.assertFalse("The loop body should not reference the array",
				code.substring(loopStart, loopEnd).contains("out["));
	}

	/**
	 * Verifies that a real reduction computation produces identical results with
	 * accumulator promotion enabled and disabled, and that both match the
	 * directly computed sum. This is a differential test that catches cases
	 * where promotion changes the output.
	 */
	@Test(timeout = 120000)
	public void promotionMatchesDisabled() {
		int rows = 8;
		int columns = 32;

		PackedCollection data = new PackedCollection(shape(rows, columns)).randFill();

		double[] expected = new double[rows];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < columns; c++) {
				expected[r] += data.toDouble(r * columns + c);
			}
		}

		double[] disabled = evaluateRowSums(data, rows, false);
		double[] enabled = evaluateRowSums(data, rows, true);

		for (int r = 0; r < rows; r++) {
			// The kernel may run at FP32, so the comparison against the
			// double-precision reference uses a correspondingly loose tolerance
			Assert.assertEquals("Row " + r + " differs from the expected sum with promotion disabled",
					expected[r], disabled[r], 1e-4);
			Assert.assertEquals("Row " + r + " differs from the expected sum with promotion enabled",
					expected[r], enabled[r], 1e-4);
			Assert.assertEquals("Row " + r + " differs between promotion enabled and disabled",
					disabled[r], enabled[r], 1e-6);
		}
	}

	/**
	 * Evaluates a per-row sum reduction with the specified promotion setting
	 * and returns the resulting row totals.
	 *
	 * @param data the collection whose rows are summed
	 * @param rows the number of rows
	 * @param enablePromotion whether accumulator promotion is enabled during compilation
	 * @return the row sums
	 */
	private double[] evaluateRowSums(PackedCollection data, int rows, boolean enablePromotion) {
		boolean previous = ScopeSettings.enableAccumulatorPromotion;
		ScopeSettings.enableAccumulatorPromotion = enablePromotion;

		try {
			PackedCollection output = (PackedCollection) cp(data).traverse(1).sum().get().evaluate();

			double[] result = new double[rows];
			for (int r = 0; r < rows; r++) {
				result[r] = output.toDouble(r);
			}
			return result;
		} finally {
			ScopeSettings.enableAccumulatorPromotion = previous;
		}
	}

	/**
	 * Creates a reference to the element of the given array at the loop-invariant
	 * position {@code _gid}. Every call produces a structurally equal reference,
	 * matching how destination and read references coincide in generated kernels.
	 */
	private Expression<Double> outElement(ArrayVariable<Double> array) {
		Expression<Integer> pos = new StaticReference<>(Integer.class, "_gid");
		return new InstanceReference(array, pos, pos);
	}

	/**
	 * Creates a reference to the element of the given array at the loop index
	 * {@code _test_i}, which varies on every iteration.
	 */
	private Expression<Double> inElement(ArrayVariable<Double> array) {
		Expression<Integer> pos = new StaticReference<>(Integer.class, "_test_i");
		return new InstanceReference(array, pos, pos);
	}

	/**
	 * Counts declarations in the given statement list whose name marks them as
	 * promoted accumulators (containing {@code _acc}).
	 */
	private long countAccumulatorDeclarations(List<Statement<?>> statements) {
		return statements.stream()
				.filter(s -> s instanceof ExpressionAssignment)
				.map(s -> (ExpressionAssignment<?>) s)
				.filter(ExpressionAssignment::isDeclaration)
				.filter(a -> {
					Expression<?> dest = a.getDestination();
					return dest instanceof StaticReference
							&& ((StaticReference<?>) dest).getName() != null
							&& ((StaticReference<?>) dest).getName().contains("_acc");
				})
				.count();
	}

	/**
	 * Checks whether any assignment in the descendant scopes of the given scope
	 * references the given array.
	 */
	private boolean bodyReferencesArray(Scope<?> scope, Variable<?, ?> array) {
		for (Scope<?> child : scope.getChildren()) {
			for (Statement<?> stmt : child.getStatements()) {
				if (stmt instanceof ExpressionAssignment) {
					ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;
					if (assignment.getDestination() != null
							&& assignment.getDestination().containsReference(array)) {
						return true;
					}
					if (assignment.getExpression().containsReference(array)) {
						return true;
					}
				}
			}

			if (bodyReferencesArray(child, array)) return true;
		}

		return false;
	}
}
