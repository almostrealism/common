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

package io.almostrealism.scope.test;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.scope.Scope;
import io.almostrealism.sequence.DefaultIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tests that the common-subexpression replacement performed while a {@link Scope} is
 * simplified produces the same code every time it is given the same statements.
 *
 * <p>Each declared replacement is named by a fresh {@link StaticReference}, and every
 * such reference has the same hash. When the targets are nested, applying one
 * replacement can absorb another, so the order in which they are applied decides the
 * generated code. That order must not come from hash iteration: a hash table holding
 * many references with the same hash orders them by identity, which differs between
 * two compilations of the same kernel and between two runs of the same program.</p>
 *
 * <p>It also tests that a declared replacement is given a type that keeps the arithmetic
 * of the code referring to it unchanged.</p>
 */
public class ScopeReplacementOrderTest extends TestSuiteBase {

	/** Number of nested targets; enough that equal hashes share one treeified bucket. */
	private static final int DEPTH = 12;

	/** Number of independent compilations compared with the first. */
	private static final int COMPILATIONS = 25;

	/**
	 * Replacing the same nested targets in the same statement yields identical code
	 * on every compilation, and the replacement actually takes place.
	 */
	@Test(timeout = 60000)
	public void nestedReplacementsAreDeterministic() {
		List<String> first = replace();

		// The outermost target is the statement's own expression, so it is already
		// declared; every other target gets a declaration ahead of the statement
		Assert.assertEquals("every nested target should be declared", DEPTH, first.size());

		for (int i = 1; i < COMPILATIONS; i++) {
			Assert.assertEquals("compilation " + i + " differs from the first", first, replace());
		}
	}

	/**
	 * A replaced expression that refers to the kernel index is declared as a 64-bit
	 * integer, even though the expression is typed as {@link Integer} and its value fits
	 * in 32 bits. Every generated language renders the kernel index as 64-bit, so the
	 * expression was evaluated in 64 bits wherever it appeared inline; a 32-bit variable
	 * makes the product it takes part in, here a row multiplied by a stride of
	 * {@code 1048512}, overflow. This is how the full-Jacobian weight gradient of a
	 * 256 by 64 dense layer over 64 rows came to be wrong from weight row 64 onward.
	 */
	@Test(timeout = 60000)
	public void kernelIndexReplacementIsDeclaredLong() {
		Expression<?> kernel = new KernelIndex().withLimit(16384);
		Expression<?> row = kernel.multiply(64).add(16).imod(1048576).divide(64);
		Assert.assertEquals(Integer.class, row.getType());
		Assert.assertTrue(row.upperBound(null).getAsLong() <= Integer.MAX_VALUE);

		Expression<?> independent = new DefaultIndex("j", 4096).add(16).imod(64);

		List<Statement<?>> statements = new ArrayList<>();
		statements.add(new ExpressionAssignment(true, new StaticReference<>(Long.class, "out"),
				row.multiply(1048512).add(independent.multiply(64))));

		List<Statement<?>> result = new ReplacementScope().replace(statements, List.of(row, independent));
		Assert.assertEquals(3, result.size());
		Assert.assertEquals(Long.class, declaredType(result, row));
		Assert.assertEquals(Integer.class, declaredType(result, independent));
	}

	/**
	 * Returns the type of the variable declared for the given replaced expression.
	 *
	 * @param statements the statements produced by replacement
	 * @param replaced   the expression that was replaced
	 * @return the declared type of the variable holding it
	 */
	private static Class<?> declaredType(List<Statement<?>> statements, Expression<?> replaced) {
		return statements.stream()
				.map(s -> (ExpressionAssignment<?>) s)
				.filter(a -> a.isDeclaration() && a.getExpression().equals(replaced))
				.map(a -> a.getDestination().getType())
				.findFirst().orElseThrow(() -> new AssertionError("no declaration of " + replaced));
	}

	/**
	 * Builds a statement over a chain of nested targets, alternating products and sums
	 * so that each target contains the previous one, and returns the code of the
	 * statements produced by replacing them.
	 *
	 * @return the rendered statements after replacement
	 */
	private List<String> replace() {
		Expression<?> chain = new StaticReference<>(Double.class, "x0");
		List<Expression<?>> targets = new ArrayList<>();

		for (int i = 1; i <= DEPTH; i++) {
			Expression<?> next = new StaticReference<>(Double.class, "x" + i);
			chain = i % 2 == 1 ? chain.multiply(next) : chain.add(next);
			targets.add(chain);
		}

		List<Statement<?>> statements = new ArrayList<>();
		statements.add(new ExpressionAssignment(true,
				new StaticReference<>(Double.class, "out"), chain));

		return new ReplacementScope().replace(statements, targets).stream()
				.map(s -> {
					ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) s;
					return assignment.getDestination().signature() + "=" +
							assignment.getExpression().signature();
				})
				.collect(Collectors.toList());
	}

	/** A {@link Scope} that exposes its replacement step to this test. */
	private static class ReplacementScope extends Scope<Void> {
		/** Creates a scope with a fixed name, so declared references are named alike. */
		ReplacementScope() {
			super("replacementOrder");
		}

		/**
		 * Replaces the given targets in the given statements.
		 *
		 * @param statements the statements to process
		 * @param targets    the replacement candidates, in priority order
		 * @return the declarations followed by the rewritten statements
		 */
		List<Statement<?>> replace(List<Statement<?>> statements, List<Expression<?>> targets) {
			return processReplacements(statements, () -> targets);
		}
	}
}
