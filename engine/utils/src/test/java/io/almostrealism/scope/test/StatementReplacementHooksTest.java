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
import io.almostrealism.code.Termination;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.ExpressionCache;
import io.almostrealism.scope.Scope;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tests the hooks through which a {@link Statement} takes part in common sub-expression
 * replacement ({@link Statement#isDeclarationOf}, {@link Statement#computes} and
 * {@link Statement#replaceComputed}), the replacement's handling of statements that compute
 * nothing and of values already declared, and
 * {@link ExpressionCache#getCurrentFrequentExpressions()}.
 */
public class StatementReplacementHooksTest extends TestSuiteBase {

	/** An assignment declares a variable for its expression only when it is a declaration. */
	@Test(timeout = 30000)
	public void assignmentDeclaresOnlyItsOwnExpressionWhenDeclaring() {
		Expression<?> product = product("a", "b");

		ExpressionAssignment<?> declaration = new ExpressionAssignment(true, ref("v"), product);
		ExpressionAssignment<?> assignment = new ExpressionAssignment(false, ref("v"), product);

		Assert.assertTrue(declaration.isDeclarationOf(product));
		Assert.assertTrue(declaration.isDeclarationOf(product("a", "b")));
		Assert.assertFalse(declaration.isDeclarationOf(product("a", "c")));
		Assert.assertFalse("a part of the expression is not what is declared",
				declaration.isDeclarationOf(ref("a")));
		Assert.assertFalse("an assignment that is not a declaration declares nothing",
				assignment.isDeclarationOf(product));
	}

	/** An assignment computes what occurs in its expression, and not its destination. */
	@Test(timeout = 30000)
	public void assignmentComputesItsExpressionNotItsDestination() {
		Expression<?> product = product("a", "b");
		ExpressionAssignment<?> assignment = new ExpressionAssignment(false, ref("out"), product.add(ref("c")));

		Assert.assertTrue(assignment.computes(product));
		Assert.assertTrue(assignment.computes(ref("c")));
		Assert.assertFalse(assignment.computes(product("a", "c")));
		Assert.assertFalse("the destination is not computed", assignment.computes(ref("out")));
	}

	/**
	 * Replacing a computed value rewrites the expression, keeps the destination and the
	 * declaration flag, and returns the same assignment when the target is not computed.
	 */
	@Test(timeout = 30000)
	public void replaceComputedRewritesOnlyTheExpression() {
		Expression<?> product = product("a", "b");
		StaticReference<Double> out = ref("out");
		ExpressionAssignment<?> assignment = new ExpressionAssignment(true, out, product.add(out));

		ExpressionAssignment<?> replaced = assignment.replaceComputed(product, ref("v"));
		Assert.assertNotSame(assignment, replaced);
		Assert.assertTrue(replaced.isDeclaration());
		Assert.assertSame(out, replaced.getDestination());
		Assert.assertEquals(ref("v").add(out), replaced.getExpression());

		ExpressionAssignment<?> kept = assignment.replaceComputed(out, ref("w"));
		Assert.assertSame("the destination is kept although it occurs in the expression",
				out, kept.getDestination());
		Assert.assertEquals(product.add(ref("w")), kept.getExpression());

		Assert.assertSame("an assignment that does not compute the target is returned unchanged",
				assignment, assignment.replaceComputed(product("x", "y"), ref("v")));
	}

	/** A statement that does not say otherwise declares and computes nothing, and is kept as it is. */
	@Test(timeout = 30000)
	public void defaultHooksLeaveTheStatementAlone() {
		Statement<?> termination = new Termination();
		Expression<?> product = product("a", "b");

		Assert.assertFalse(termination.isDeclarationOf(product));
		Assert.assertFalse(termination.computes(product));
		Assert.assertSame(termination, termination.replaceComputed(product, ref("v")));
	}

	/**
	 * A candidate that a statement already declares is not declared again, a candidate that no
	 * statement computes is not declared at all, and a statement that computes nothing is
	 * passed through unchanged, in place.
	 */
	@Test(timeout = 30000)
	public void replacementSkipsDeclaredAndUnusedCandidates() {
		Expression<?> declared = product("a", "b");
		Expression<?> unused = product("p", "q");

		List<Statement<?>> statements = new ArrayList<>();
		statements.add(new ExpressionAssignment(true, ref("v"), declared));
		Termination termination = new Termination();
		statements.add(termination);
		statements.add(new ExpressionAssignment(false, ref("out"), declared.add(ref("c"))));

		List<Statement<?>> result = new HooksScope().replace(statements, () -> List.of(declared, unused));
		Assert.assertSame("nothing should be replaced", statements, result);
	}

	/**
	 * A candidate computed by an assignment that follows a statement computing nothing is
	 * declared ahead of every statement, and the statement computing nothing is kept as it is.
	 */
	@Test(timeout = 30000)
	public void replacementKeepsStatementsThatComputeNothing() {
		Expression<?> product = product("a", "b");

		List<Statement<?>> statements = new ArrayList<>();
		Termination termination = new Termination();
		statements.add(termination);
		statements.add(new ExpressionAssignment(false, ref("out"), product.add(ref("c"))));

		List<Statement<?>> result = new HooksScope().replace(statements, () -> List.of(product));
		Assert.assertEquals(3, result.size());

		ExpressionAssignment<?> declaration = (ExpressionAssignment<?>) result.get(0);
		Assert.assertTrue(declaration.isDeclaration());
		Assert.assertEquals(product, declaration.getExpression());

		Assert.assertSame(termination, result.get(1));

		ExpressionAssignment<?> rewritten = (ExpressionAssignment<?>) result.get(2);
		Assert.assertEquals("out", rewritten.getDestination().getDeclaredName());
		Assert.assertEquals(declaration.getDestination().add(ref("c")), rewritten.getExpression());
	}

	/** With no cache active, or with an empty one, there are no frequent expressions. */
	@Test(timeout = 30000)
	public void currentFrequentExpressionsWithoutCandidates() {
		ExpressionCache.bypass(() ->
				Assert.assertEquals(List.of(), ExpressionCache.getCurrentFrequentExpressions()));

		ExpressionCache empty = new ExpressionCache();
		Assert.assertEquals(List.of(), empty.use(
				(Supplier<List<Expression<?>>>) ExpressionCache::getCurrentFrequentExpressions));
	}

	/** With a cache active, its ranking is returned, the very list it returns itself. */
	@Test(timeout = 30000)
	public void currentFrequentExpressionsAreThoseOfTheActiveCache() {
		ExpressionCache cache = new ExpressionCache();
		Expression<?> expression = chain();

		for (int i = 0; i < 4; i++) {
			cache.get(expression);
		}

		List<Expression<?>> ranking = cache.getFrequentExpressions();
		Assert.assertEquals(List.of(expression), ranking);
		Assert.assertSame(ranking, cache.use(
				(Supplier<List<Expression<?>>>) ExpressionCache::getCurrentFrequentExpressions));
	}

	/**
	 * Returns a reference of type double with the given name.
	 *
	 * @param name the name of the reference
	 * @return the reference
	 */
	private static StaticReference<Double> ref(String name) {
		return new StaticReference<>(Double.class, name);
	}

	/**
	 * Returns the product of two references with the given names.
	 *
	 * @param left  the name of the left operand
	 * @param right the name of the right operand
	 * @return the product
	 */
	private static Expression<?> product(String left, String right) {
		return ref(left).multiply(ref(right));
	}

	/**
	 * Returns an expression of alternating products and sums, costly enough to be kept by
	 * an {@link ExpressionCache}.
	 *
	 * @return the expression
	 */
	private static Expression<?> chain() {
		Expression<?> chain = ref("e0");

		for (int i = 1; i <= 16; i++) {
			chain = i % 2 == 1 ? chain.multiply(ref("e" + i)) : chain.add(ref("e" + i));
		}

		return chain;
	}

	/** A {@link Scope} that exposes its replacement step to this test. */
	private static class HooksScope extends Scope<Void> {
		/** Creates a scope with a fixed name. */
		HooksScope() {
			super("replacementHooks");
		}

		/**
		 * Replaces the candidates offered by the given supplier in the given statements.
		 *
		 * @param statements the statements to process
		 * @param candidates the supplier of replacement candidates
		 * @return the declarations followed by the rewritten statements
		 */
		List<Statement<?>> replace(List<Statement<?>> statements, Supplier<List<Expression<?>>> candidates) {
			return processReplacements(statements, candidates);
		}
	}
}
