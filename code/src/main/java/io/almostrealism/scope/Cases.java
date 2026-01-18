/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.profile.OperationMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A specialized {@link Scope} that represents conditional branching (if-else-if-else chains).
 * <p>{@link Cases} extends {@link Scope} to support multiple conditional branches where each
 * child scope is associated with a boolean condition. During code generation, this produces
 * standard if-else-if-else control flow structures.</p>
 *
 * <h2>Structure</h2>
 * <p>A {@link Cases} object maintains:</p>
 * <ul>
 *   <li>A list of boolean {@link Expression}s (conditions) corresponding to each branch</li>
 *   <li>Child scopes representing the code to execute for each condition</li>
 *   <li>An optional else branch (a child scope without a corresponding condition)</li>
 * </ul>
 *
 * <h2>Generated Code</h2>
 * <p>When written to a {@link CodePrintWriter}, produces code like:</p>
 * <pre>{@code
 * if (condition1) {
 *     // child scope 1
 * } else if (condition2) {
 *     // child scope 2
 * } else {
 *     // child scope 3 (no condition)
 * }
 * }</pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Cases<Double> cases = new Cases<>("myCases", metadata);
 * cases.addCase(condition1, scope1);
 * cases.addCase(condition2, scope2);
 * cases.add(elseScope); // No condition = else branch
 * }</pre>
 *
 * @param <T> the type of value returned by this scope
 *
 * @see Scope
 * @see Expression
 */
public class Cases<T> extends Scope<T> {
	/**
	 * List of boolean conditions for each branch.
	 * The i-th condition corresponds to the i-th child scope.
	 * If there are fewer conditions than children, the last child is the else branch.
	 */
	private List<Expression<Boolean>> conditions;

	/**
	 * Creates an empty {@link Cases} with no name or conditions.
	 */
	public Cases() {
		conditions = new ArrayList<>();
	}

	/**
	 * Creates a {@link Cases} with the specified name.
	 *
	 * @param name the unique identifier for this cases scope
	 */
	public Cases(String name) {
		this();
		setName(name);
	}

	/**
	 * Creates a {@link Cases} with the specified name and metadata.
	 *
	 * @param name     the unique identifier for this cases scope
	 * @param metadata operation metadata for profiling and debugging;
	 *                 a copy is made to prevent external modification
	 * @throws IllegalArgumentException if metadata is null
	 */
	public Cases(String name, OperationMetadata metadata) {
		this(name);
		setMetadata(new OperationMetadata(metadata));

		if (metadata == null)
			throw new IllegalArgumentException();
	}

	/**
	 * Returns the list of boolean conditions for the branches.
	 * <p>The i-th condition corresponds to the i-th child scope.
	 * If there are more children than conditions, the last child(ren)
	 * are else branches.</p>
	 *
	 * @return the mutable list of conditions
	 */
	public List<Expression<Boolean>> getConditions() { return conditions; }

	/**
	 * Adds a conditional case with the given condition and scope.
	 * <p>The scope will be executed when the condition evaluates to true.
	 * This method does not support alternative scopes (else branches);
	 * use {@link #add(Scope)} to add an else branch.</p>
	 *
	 * @param condition the boolean condition for this case; if null, the scope
	 *                  is added as an else branch
	 * @param scope     the scope to execute when the condition is true
	 * @param altScope  alternative scope (must be null; not supported for Cases)
	 * @return the provided scope
	 * @throws UnsupportedOperationException if altScope is not null
	 */
	@Override
	public Scope<T> addCase(Expression<Boolean> condition, Scope<T> scope, Scope<T> altScope) {
		if (altScope != null) throw new UnsupportedOperationException();
		if (condition != null) conditions.add(condition);
		getChildren().add(scope);
		return scope;
	}

	/**
	 * Collects and maps all arguments for this cases scope, including dependencies
	 * from the conditions themselves.
	 *
	 * @param <A>    the type to map arguments to
	 * @param mapper function to transform each argument
	 * @return list of mapped arguments with duplicates removed
	 */
	protected <A> List<A> arguments(Function<Argument<?>, A> mapper) {
		List<Argument<?>> args = new ArrayList<>();
		args.addAll(extractArgumentDependencies(getConditions().stream()
				.flatMap(e -> e.getDependencies().stream()).collect(Collectors.toList())));
		args.addAll(super.arguments(Function.identity()));
		return Scope.removeDuplicateArguments(args).stream().map(mapper).collect(Collectors.toList());
	}

	/**
	 * Writes this cases scope to the specified {@link CodePrintWriter}.
	 * <p>Generates if-else-if-else code structure. Each child scope with a
	 * corresponding condition produces an "if" or "else if" block. Any remaining
	 * children without conditions produce an "else" block.</p>
	 *
	 * @param w the code print writer to write to
	 * @throws UnsupportedOperationException if a condition evaluates to literal "false"
	 */
	@Override
	public void write(CodePrintWriter w) {
		w.renderMetadata(getMetadata());
		for (Method m : getMethods()) { w.println(m); }
		for (Statement s : getStatements()) { w.println(s); }
		for (ExpressionAssignment<?> v : getVariables()) { w.println(v); }

		for (int i = 0; i < getChildren().size(); i++) {
			if (i < getConditions().size()) {
				String c = getConditions().get(i).getExpression(w.getLanguage());
				if ("false".equals(c)) {
					throw new UnsupportedOperationException();
				}

				String pre = i > 0 ? "else if (" : "if (";
				w.println(pre + c + ") {");
			} else {
				w.println(" else {");
			}

			getChildren().get(i).write(w);
			w.println("}");
		}

		for (Metric m : getMetrics()) { w.println(m); }
		w.flush();
	}

	/**
	 * Creates a simplified version of this cases scope.
	 * <p>Simplifies both the conditions and the child scopes using the provided context.</p>
	 *
	 * @param context the kernel structure context providing simplification rules
	 * @param depth   the current recursion depth
	 * @return a new simplified cases scope
	 */
	@Override
	public Scope<T> simplify(KernelStructureContext context, int depth) {
		Cases<T> scope = (Cases<T>) super.simplify(context, depth);
		scope.getConditions().addAll(getConditions().stream().map(c -> c.simplify(context, depth + 1)).collect(Collectors.toList()));
		return scope;
	}

	/**
	 * Generates a new cases scope with the given children while preserving this scope's configuration.
	 *
	 * @param children the child scopes to include in the generated scope
	 * @return a new cases scope with the specified children
	 */
	@Override
	public Cases<T> generate(List<Scope<T>> children) {
		Cases<T> scope = getMetadata() == null ? new Cases<>(getName()) : new Cases<>(getName(), getMetadata());
		scope.getChildren().addAll(children);
		return scope;
	}
}
