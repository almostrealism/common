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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Read-only walker that collects every {@link Expression} root reachable from a
 * {@link Scope} tree. Designed as the bridge between the compile-pipeline output
 * (a {@link Scope}) and {@link ExpressionDuplicationScanner}, which operates on
 * a flat collection of expression roots.
 *
 * <h2>What gets collected</h2>
 * <p>For each scope reached by walking {@link Scope#getChildren()} the collector
 * pulls expressions from:</p>
 * <ul>
 *   <li>{@link Scope#getVariables()} — both the destination and expression of
 *       every {@link ExpressionAssignment}.</li>
 *   <li>{@link Scope#getStatements()} — same for any statement that is an
 *       {@link ExpressionAssignment}. Other statement types are deliberately
 *       skipped; if a workload exposes them and they matter, this is the place
 *       to extend.</li>
 *   <li>{@link Scope#getMethods()} — every {@link Method}, since
 *       {@link Method} {@code extends Expression} and its arguments are children
 *       of that expression.</li>
 *   <li>{@link Cases#getConditions()} — every boolean condition expression
 *       attached to a {@link Cases} scope.</li>
 *   <li>{@link Repeated#getCondition()} and {@link Repeated#getInterval()} — the
 *       loop condition and stride expressions.</li>
 * </ul>
 *
 * <p>Identity-shared sub-trees are not removed up front: the collector simply
 * appends every Expression it finds. The downstream scanner is responsible for
 * deduplicating identity-shared nodes during its walk, which is its whole point.</p>
 *
 * <p>The collector never mutates the scope tree, never calls {@link Expression#simplify}
 * or any other rewriter, and is safe to invoke on an in-use compiled scope.</p>
 */
public class ScopeExpressionCollector {

	/**
	 * Static-only utility.
	 */
	private ScopeExpressionCollector() {
	}

	/**
	 * Walks the given scope tree and returns every {@link Expression} root reachable
	 * from variables, statements (when {@link ExpressionAssignment}), methods,
	 * {@link Cases} conditions and {@link Repeated} bounds.
	 *
	 * <p>Scope-tree traversal is iterative and identity-deduplicated so that DAG-shared
	 * sub-scopes are visited only once.</p>
	 *
	 * @param root the root scope to walk; {@code null} is treated as an empty result
	 * @return a list of expression roots in walk order (not deduplicated by equals)
	 */
	public static List<Expression<?>> collect(Scope<?> root) {
		List<Expression<?>> roots = new ArrayList<>();
		if (root == null) return roots;

		IdentityHashMap<Scope<?>, Boolean> visited = new IdentityHashMap<>();
		ArrayDeque<Scope<?>> stack = new ArrayDeque<>();
		stack.push(root);

		while (!stack.isEmpty()) {
			Scope<?> s = stack.pop();
			if (visited.put(s, Boolean.TRUE) != null) continue;

			collectFromVariables(s, roots);
			collectFromStatements(s, roots);
			collectFromMethods(s, roots);
			collectFromCases(s, roots);
			collectFromRepeated(s, roots);

			for (Scope<?> child : s.getChildren()) {
				if (child != null) stack.push(child);
			}
		}

		return roots;
	}

	/**
	 * Appends the destination and expression of every variable assignment in the
	 * scope to the given root list.
	 *
	 * @param s     the scope to read
	 * @param roots the list to append into
	 */
	private static void collectFromVariables(Scope<?> s, List<Expression<?>> roots) {
		for (ExpressionAssignment<?> a : s.getVariables()) {
			if (a == null) continue;
			if (a.getDestination() != null) roots.add(a.getDestination());
			if (a.getExpression() != null) roots.add(a.getExpression());
		}
	}

	/**
	 * Appends the destination and expression of every {@link ExpressionAssignment}
	 * statement in the scope to the given root list. Other statement types are
	 * deliberately skipped.
	 *
	 * @param s     the scope to read
	 * @param roots the list to append into
	 */
	private static void collectFromStatements(Scope<?> s, List<Expression<?>> roots) {
		for (Statement<?> stmt : s.getStatements()) {
			if (stmt instanceof ExpressionAssignment) {
				ExpressionAssignment<?> a = (ExpressionAssignment<?>) stmt;
				if (a.getDestination() != null) roots.add(a.getDestination());
				if (a.getExpression() != null) roots.add(a.getExpression());
			}
		}
	}

	/**
	 * Appends every {@link Method} in the scope to the given root list. Method
	 * arguments are reached as Expression children of the Method during the
	 * downstream scan.
	 *
	 * @param s     the scope to read
	 * @param roots the list to append into
	 */
	private static void collectFromMethods(Scope<?> s, List<Expression<?>> roots) {
		for (Method<?> m : s.getMethods()) {
			if (m != null) roots.add(m);
		}
	}

	/**
	 * If the scope is a {@link Cases}, appends every branch condition expression
	 * to the given root list. Otherwise does nothing.
	 *
	 * @param s     the scope to read
	 * @param roots the list to append into
	 */
	private static void collectFromCases(Scope<?> s, List<Expression<?>> roots) {
		if (s instanceof Cases) {
			for (Expression<Boolean> c : ((Cases<?>) s).getConditions()) {
				if (c != null) roots.add(c);
			}
		}
	}

	/**
	 * If the scope is a {@link Repeated}, appends the loop condition and stride
	 * expressions to the given root list, along with the destination and
	 * expression of every epilogue assignment. Otherwise does nothing.
	 *
	 * @param s     the scope to read
	 * @param roots the list to append into
	 */
	private static void collectFromRepeated(Scope<?> s, List<Expression<?>> roots) {
		if (s instanceof Repeated) {
			Repeated<?> r = (Repeated<?>) s;
			if (r.getCondition() != null) roots.add(r.getCondition());
			if (r.getInterval() != null) roots.add(r.getInterval());

			for (Statement<?> stmt : r.getEpilogue()) {
				if (stmt instanceof ExpressionAssignment) {
					ExpressionAssignment<?> a = (ExpressionAssignment<?>) stmt;
					if (a.getDestination() != null) roots.add(a.getDestination());
					if (a.getExpression() != null) roots.add(a.getExpression());
				}
			}
		}
	}
}
