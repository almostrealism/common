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

package io.almostrealism.scope;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The common sub-expressions of the statements of a {@link Scope}, each declared once as a
 * variable of the scope ahead of the statements, which then obtain the value from that variable
 * rather than computing it again. {@link Scope#simplify(io.almostrealism.kernel.KernelStructureContext, int)}
 * replaces them in its simplified statements through {@link Scope#processReplacements}.
 *
 * <p>The candidates are offered by a supplier; while a kernel is compiled it offers the
 * {@link ExpressionCache#getCurrentFrequentExpressions() frequent expressions} of the active
 * expression cache. The replacement proceeds in rounds:</p>
 * <ol>
 *   <li>Each candidate that has not been reviewed before is declared if a statement
 *       {@link Statement#computes(Expression) computes} it and no statement
 *       {@link Statement#isDeclarationOf(Expression) declares} it already, until
 *       {@link ScopeSettings#getMaximumReplacements()} have been declared.</li>
 *   <li>If any was declared, every statement
 *       {@link Statement#replaceComputed(Expression, Expression) obtains} every value declared
 *       so far from its variable.</li>
 *   <li>The candidates are asked for again, and those not yet reviewed are reviewed by the next
 *       round, until no candidate is left or the maximum has been reached.</li>
 * </ol>
 *
 * <p>Replacements are applied in the order they were declared. The order changes the
 * generated code, since a replacement can absorb part of another target, so it must
 * not depend on hash iteration order: every {@link StaticReference} has the same hash,
 * and a hash table holding many of them orders them by identity, which differs from
 * one run to the next.</p>
 *
 * <p>A kernel can offer thousands of candidates to each of thousands of scopes, so a
 * round does no more per candidate than it must. When the supplier returns the list it
 * returned before, as {@link ExpressionCache#getFrequentExpressions()} does while the cache
 * is unchanged, every candidate has been reviewed and the replacement ends; the targets a
 * round reviewed, which are the first ones its iteration visited, are only recorded when a
 * later round must exclude them.</p>
 *
 * <p>An instance accumulates the declarations it makes, so it holds the replacement of one list
 * of statements.</p>
 *
 * @see Scope#processReplacements
 * @see ExpressionCache
 */
public class CommonSubexpressions implements ConsoleFeatures {
	/** The scope that names the declared variables. */
	private final Scope<?> scope;

	/** The declaration of the variable for each replaced sub-expression, in the order they were declared. */
	private final List<ExpressionAssignment<?>> declarations;

	/** The statements, rewritten after each round to obtain every declared value from its variable. */
	private List<Statement<?>> statements;

	/**
	 * Prepares the replacement of the common sub-expressions of the given statements by
	 * variables of the given scope.
	 *
	 * @param scope      the scope that names the declared variables
	 * @param statements the statements whose common sub-expressions are replaced
	 */
	public CommonSubexpressions(Scope<?> scope, List<Statement<?>> statements) {
		this.scope = scope;
		this.declarations = new ArrayList<>();
		this.statements = statements;
	}

	/**
	 * Replaces the candidates offered by the given supplier, in rounds as described for this
	 * class, and returns the statements that result.
	 *
	 * @param candidates the supplier of the candidates for replacement, in priority order; it is
	 *                   asked again after every round, and returning the very list it returned
	 *                   before says that it offers nothing new
	 * @return the declarations of the replaced sub-expressions followed by the rewritten
	 *         statements, or the statements as given if nothing was replaced
	 */
	public List<Statement<?>> replace(Supplier<List<Expression<?>>> candidates) {
		Set<Expression<?>> processed = new HashSet<>();
		List<Expression<?>> offered = candidates.get();
		Set<Expression<?>> targets = new HashSet<>(offered);

		while (!targets.isEmpty() && !isFull()) {
			if (Scope.verbose) log("Processing " + targets.size() + " replacement targets");

			int declared = declarations.size();
			int reviewed = review(targets);

			// The process will be repeated, but with the updated statements
			// and any new targets that may have been identified in the process
			if (declarations.size() > declared) rewrite();

			List<Expression<?>> refreshed = candidates.get();
			if (refreshed == offered) break;

			// Record the reviewed targets, as they should not be visited again
			Iterator<Expression<?>> visited = targets.iterator();
			for (int i = 0; i < reviewed; i++) {
				processed.add(visited.next());
			}

			// Reset the targets to the new replacement
			// opportunities that have not been reviewed
			offered = refreshed;
			targets.clear();
			refreshed.stream()
					.filter(Predicate.not(processed::contains))
					.forEach(targets::add);
		}

		if (declarations.isEmpty()) return statements;

		List<Statement<?>> result = new ArrayList<>(declarations);
		result.addAll(statements);
		return result;
	}

	/**
	 * Reviews the given targets in their iteration order, declaring each that is
	 * {@link #isReplaceable(Expression) replaceable}, until the maximum number of replacements
	 * has been declared.
	 *
	 * @param targets the targets of this round
	 * @return the number of targets reviewed, which are the first ones the iteration visited
	 */
	private int review(Set<Expression<?>> targets) {
		int reviewed = 0;

		for (Expression<?> e : targets) {
			if (isFull()) break;
			if (isReplaceable(e)) declare(e);
			reviewed++;
		}

		return reviewed;
	}

	/**
	 * Returns whether {@link ScopeSettings#getMaximumReplacements()} sub-expressions have been
	 * declared, so that no more may be.
	 *
	 * @return {@code true} if no further sub-expression may be declared
	 */
	private boolean isFull() {
		return declarations.size() >= ScopeSettings.getMaximumReplacements();
	}

	/**
	 * Returns whether some statement {@link Statement#computes(Expression) computes}
	 * {@code e} and none {@link Statement#isDeclarationOf(Expression) declares} it already.
	 *
	 * @param e the candidate
	 * @return {@code true} if declaring a variable for {@code e} lets a statement use it
	 */
	private boolean isReplaceable(Expression<?> e) {
		boolean computed = false;

		for (Statement<?> statement : statements) {
			if (statement.isDeclarationOf(e)) return false;
			computed = computed || statement.computes(e);
		}

		return computed;
	}

	/**
	 * Declares a variable of the {@link #scope} holding the value of {@code e}, of the
	 * {@link Expression#getDeclarationType() type} that keeps the arithmetic of the
	 * statements referring to it unchanged.
	 *
	 * @param e the sub-expression to declare
	 */
	private void declare(Expression<?> e) {
		StaticReference ref = new StaticReference<>(e.getDeclarationType(), scope.nextVariableName());
		declarations.add(new ExpressionAssignment(true, ref, e));
	}

	/**
	 * Rewrites every statement to obtain the value of each declared sub-expression from its
	 * variable, applying the declarations in the order they were made.
	 */
	private void rewrite() {
		List<Statement<?>> next = new ArrayList<>(statements.size());

		for (Statement<?> statement : statements) {
			Statement<?> rewritten = statement;

			for (ExpressionAssignment<?> declaration : declarations) {
				rewritten = rewritten.replaceComputed(declaration.getExpression(), declaration.getDestination());
			}

			next.add(rewritten);
		}

		statements = next;
	}

	/**
	 * Returns the console of the scopes whose statements this replaces.
	 *
	 * @return {@link Scope#console}
	 */
	@Override
	public Console console() { return Scope.console; }
}
