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
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Tests how the common-subexpression replacement performed while a {@link Scope} is
 * simplified treats the candidates it is offered again after each round of replacements.
 *
 * <p>A supplier that returns the very list it returned before, as the expression cache
 * does while no lookup has changed it, offers nothing new, and the replacement must end
 * with the same result as for a supplier that returns an equal copy of that list. A
 * supplier that returns a different list offers new candidates, which a further round
 * must review.</p>
 */
public class ScopeReplacementRoundsTest extends TestSuiteBase {

	/**
	 * Offering the same list again gives the same statements as offering an equal copy of
	 * it, both when every candidate fits within the maximum number of replacements and when
	 * there are more used candidates than that.
	 */
	@Test(timeout = 60000)
	public void unchangedCandidatesGiveTheResultOfEqualCopies() {
		int maximum = ScopeSettings.getMaximumReplacements();

		for (int length : new int[] { maximum / 2, maximum + 4 }) {
			List<Expression<?>> targets = new ArrayList<>();
			Expression<?> chain = chain(length, targets);

			List<String> same = replace(chain, () -> targets);
			List<String> copies = replace(chain, () -> new ArrayList<>(targets));

			Assert.assertEquals("replacement of " + length + " nested targets", copies, same);
			Assert.assertTrue("replacements should be declared", same.size() > 1);
			Assert.assertTrue("no more than the maximum number of replacements",
					same.size() <= maximum + 1);
		}
	}

	/**
	 * A candidate that is offered only after the first round, by a supplier that then
	 * returns a different list, is reviewed in a further round and declared, and the
	 * candidate the first round declared is not declared again.
	 */
	@Test(timeout = 60000)
	public void candidatesOfferedAfterTheFirstRoundAreReviewed() {
		Expression<?> left = new StaticReference<>(Double.class, "x0")
				.multiply(new StaticReference<>(Double.class, "x1"));
		Expression<?> right = new StaticReference<>(Double.class, "x2")
				.multiply(new StaticReference<>(Double.class, "x3"));

		List<Expression<?>> first = List.of(left);
		List<Integer> requests = new ArrayList<>();
		Supplier<List<Expression<?>>> candidates = () -> {
			requests.add(requests.size());
			return requests.size() == 1 ? first : List.of(left, right);
		};

		List<Statement<?>> statements = new ArrayList<>();
		statements.add(new ExpressionAssignment(true,
				new StaticReference<>(Double.class, "out"), left.add(right)));
		List<Statement<?>> result = new RoundsScope().replace(statements, candidates);

		List<Expression<?>> declared = result.stream()
				.map(s -> (ExpressionAssignment<?>) s)
				.filter(a -> !"out".equals(a.getDestination().getDeclaredName()))
				.map(ExpressionAssignment::getExpression)
				.collect(Collectors.toList());

		Assert.assertEquals("both candidates should be declared, each once", List.of(left, right), declared);
		Assert.assertTrue("a further round should have been asked for its candidates", requests.size() > 2);
	}

	/**
	 * Builds a statement over a chain of nested targets, alternating products and sums so
	 * that each target contains the previous one, collecting the targets.
	 *
	 * @param length  the number of targets
	 * @param targets the list the targets are added to, innermost first
	 * @return the expression of the statement
	 */
	private static Expression<?> chain(int length, List<Expression<?>> targets) {
		Expression<?> chain = new StaticReference<>(Double.class, "y0");

		for (int i = 1; i <= length; i++) {
			Expression<?> next = new StaticReference<>(Double.class, "y" + i);
			chain = i % 2 == 1 ? chain.multiply(next) : chain.add(next);
			targets.add(chain);
		}

		return chain;
	}

	/**
	 * Replaces the candidates offered by the given supplier in a statement that is not a
	 * declaration, and renders the resulting statements.
	 *
	 * @param expression the expression the statement assigns
	 * @param candidates the supplier of candidates
	 * @return the rendered statements after replacement
	 */
	private static List<String> replace(Expression<?> expression, Supplier<List<Expression<?>>> candidates) {
		List<Statement<?>> statements = new ArrayList<>();
		statements.add(new ExpressionAssignment(false,
				new StaticReference<>(Double.class, "out"), expression));

		return new RoundsScope().replace(statements, candidates).stream()
				.map(s -> {
					ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) s;
					return assignment.getDestination().signature() + "=" +
							assignment.getExpression().signature();
				})
				.collect(Collectors.toList());
	}

	/** A {@link Scope} that exposes its replacement step to this test. */
	private static class RoundsScope extends Scope<Void> {
		/** Creates a scope with a fixed name, so declared references are named alike. */
		RoundsScope() {
			super("replacementRounds");
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
