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

package io.almostrealism.scope;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link Scope} representing a repeated (loop) block of code.
 *
 * <p>{@code Repeated} generates a for-loop in the output code, iterating from 0
 * while the condition is true, incrementing by the specified interval.</p>
 *
 * <h2>Loop-Invariant Code Motion (LICM)</h2>
 * <p>During simplification, {@code Repeated} can optionally hoist loop-invariant
 * statements outside the loop body. A statement is loop-invariant if its expression
 * does not depend on the loop index variable or any variable assigned inside the loop.
 * This optimization can significantly reduce per-iteration computation cost.</p>
 *
 * <p>LICM is controlled by the {@code AR_LOOP_INVARIANT_HOISTING} environment variable
 * (default: enabled). When enabled, invariant statements are moved from child scopes
 * to this scope's statements list, which is rendered before the loop body.</p>
 *
 * @param <T> the return type of this scope
 * @see Scope
 * @see ScopeSettings
 */
public class Repeated<T> extends Scope<T> {

	/**
	 * Controls whether loop-invariant code motion is enabled.
	 * When true, statements that do not depend on the loop index or any
	 * variable assigned inside the loop will be hoisted outside the loop.
	 *
	 * <p><strong>Note:</strong> LICM is currently disabled by default because the
	 * current implementation does not correctly handle all loop-variant dependencies,
	 * particularly expressions containing Index references to nested loop variables.
	 * The optimization can be enabled with AR_LOOP_INVARIANT_HOISTING=true for
	 * testing, but this may cause native compiler failures with undefined variables.</p>
	 */
	public static boolean enableLoopInvariantHoisting =
			SystemUtils.isEnabled("AR_LOOP_INVARIANT_HOISTING").orElse(false);

	private Variable<Integer, ?> index;
	private Expression<Integer> interval;
	private Expression<Boolean> condition;

	public Repeated() { }

	public Repeated(String name) {
		this();
		setName(name);
	}

	public Repeated(String name, OperationMetadata metadata) {
		this(name);
		setMetadata(new OperationMetadata(metadata));

		if (metadata == null)
			throw new IllegalArgumentException();
	}

	public Repeated(Variable<Integer, ?> idx, Expression<Boolean> condition) {
		this(idx, condition, new IntegerConstant(1));
	}

	public Repeated(Variable<Integer, ?> idx, Expression<Boolean> condition, Expression<Integer> interval) {
		this();
		setIndex(idx);
		setInterval(interval);
		setCondition(condition);
	}

	public Variable<Integer, ?> getIndex() { return index; }
	public void setIndex(Variable<Integer, ?> index) { this.index = index; }

	public Expression<Integer> getInterval() { return interval; }
	public void setInterval(Expression<Integer> interval) { this.interval = interval; }

	public Expression<Boolean> getCondition() { return condition; }
	public void setCondition(Expression<Boolean> condition) { this.condition = condition; }

	@Override
	public void write(CodePrintWriter w) {
		w.renderMetadata(getMetadata());
		for (Method m : getMethods()) { w.println(m); }
		for (Statement s : getStatements()) { w.println(s); }
		for (ExpressionAssignment<?> v : getVariables()) { w.println(v); }

		w.println("for (int " + getIndex().getName() + " = 0; " + getCondition().getExpression(w.getLanguage()) + ";) {");
		for (Scope v : getChildren()) { v.write(w); }
		w.println(getIndex().getName() + " = " + getIndex().getName() + " + " + interval.getExpression(w.getLanguage()) + ";");
		w.println("}");

		for (Metric m : getMetrics()) { w.println(m); }
		w.flush();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>In addition to standard simplification, this method performs loop-invariant
	 * code motion (LICM) when {@link #enableLoopInvariantHoisting} is true. Declaration
	 * statements whose expressions do not reference the loop index variable are hoisted
	 * from child scopes to this scope's statements list, where they execute once before
	 * the loop rather than on every iteration.</p>
	 */
	@Override
	public Scope<T> simplify(KernelStructureContext context, int depth) {
		Repeated<T> scope = (Repeated<T>) super.simplify(context, depth);
		scope.setInterval(getInterval().simplify(context, depth + 1));
		scope.setCondition(getCondition().simplify(context, depth + 1));

		if (enableLoopInvariantHoisting && index != null) {
			hoistLoopInvariantStatements(scope);
		}

		return scope;
	}

	/**
	 * Identifies and hoists loop-invariant declaration statements from child scopes.
	 *
	 * <p>A declaration statement is loop-invariant if its expression does not contain
	 * a reference to the loop index variable OR any variable that is assigned inside
	 * the loop body. Such statements can be safely moved outside the loop since their
	 * value doesn't change across iterations.</p>
	 *
	 * <p>Note: This is a conservative optimization that only hoists declarations.
	 * Non-declaration assignments might have side effects or depend on values
	 * assigned earlier in the loop, so they are not hoisted.</p>
	 *
	 * @param scope the simplified scope to optimize
	 */
	private void hoistLoopInvariantStatements(Repeated<T> scope) {
		// First pass: collect all variables that are assigned inside the loop body.
		// These are loop-variant because their values change during loop execution.
		Set<String> loopAssignedVariables = collectLoopAssignedVariables(scope);

		// Collect all loop indices (this scope's index plus nested loop indices)
		List<Index> loopIndices = collectLoopIndices(scope);

		List<Statement<?>> hoisted = new ArrayList<>();

		for (Scope<T> child : scope.getChildren()) {
			List<Statement<?>> toRemove = new ArrayList<>();

			for (Statement<?> stmt : child.getStatements()) {
				if (stmt instanceof ExpressionAssignment) {
					ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;

					// Only hoist declarations (new variable definitions)
					// Non-declarations might assign to variables used elsewhere in the loop
					if (assignment.isDeclaration() && isLoopInvariant(assignment.getExpression(), loopAssignedVariables, loopIndices)) {
						hoisted.add(stmt);
						toRemove.add(stmt);
					}
				}
			}

			child.getStatements().removeAll(toRemove);
		}

		// Add hoisted statements to this scope (before the loop)
		scope.getStatements().addAll(0, hoisted);
	}

	/**
	 * Collects all Index objects from this scope and nested Repeated scopes.
	 *
	 * <p>This is necessary because expressions may reference loop indices via
	 * the Index interface rather than through Variable dependencies.</p>
	 *
	 * @param scope the scope to analyze
	 * @return a list of all Index objects from this and nested loops
	 */
	private List<Index> collectLoopIndices(Repeated<T> scope) {
		List<Index> indices = new ArrayList<>();

		// Add this scope's index if it implements Index
		Variable<Integer, ?> scopeIndex = scope.getIndex();
		if (scopeIndex instanceof Index) {
			indices.add((Index) scopeIndex);
		}

		// Recursively collect from children
		for (Scope<T> child : scope.getChildren()) {
			collectLoopIndicesRecursive(child, indices);
		}

		return indices;
	}

	/**
	 * Recursively collects Index objects from nested Repeated scopes.
	 *
	 * @param scope the scope to analyze
	 * @param indices the list to add indices to
	 */
	private void collectLoopIndicesRecursive(Scope<?> scope, List<Index> indices) {
		if (scope instanceof Repeated) {
			Repeated<?> nested = (Repeated<?>) scope;
			Variable<Integer, ?> nestedIndex = nested.getIndex();
			if (nestedIndex instanceof Index) {
				indices.add((Index) nestedIndex);
			}
		}

		for (Scope<?> child : scope.getChildren()) {
			collectLoopIndicesRecursive(child, indices);
		}
	}

	/**
	 * Collects the names of all variables that are assigned inside the loop body.
	 *
	 * <p>This includes the loop index variable, any variables declared within child
	 * scopes, and any variables that are assigned (even if declared elsewhere).</p>
	 *
	 * @param scope the scope to analyze
	 * @return a set of variable names that are assigned inside the loop
	 */
	private Set<String> collectLoopAssignedVariables(Repeated<T> scope) {
		Set<String> assignedVars = new HashSet<>();

		// The loop index itself is loop-variant
		Variable<Integer, ?> scopeIndex = scope.getIndex();
		if (scopeIndex != null && scopeIndex.getName() != null) {
			assignedVars.add(scopeIndex.getName());
		}

		// Collect all variables assigned in child scopes
		for (Scope<T> child : scope.getChildren()) {
			collectAssignedVariablesRecursive(child, assignedVars);
		}

		return assignedVars;
	}

	/**
	 * Recursively collects variable names from all assignments in a scope and its children.
	 *
	 * <p>This includes loop index variables from nested {@link Repeated} scopes, since
	 * those are loop-variant and expressions depending on them cannot be hoisted.</p>
	 *
	 * @param scope the scope to analyze
	 * @param assignedVars the set to add variable names to
	 */
	private void collectAssignedVariablesRecursive(Scope<?> scope, Set<String> assignedVars) {
		// If this scope is a nested Repeated, its index variable is loop-variant
		if (scope instanceof Repeated) {
			Repeated<?> nested = (Repeated<?>) scope;
			Variable<Integer, ?> nestedIndex = nested.getIndex();
			if (nestedIndex != null && nestedIndex.getName() != null) {
				assignedVars.add(nestedIndex.getName());
			}
		}

		// Collect from statements
		for (Statement<?> stmt : scope.getStatements()) {
			if (stmt instanceof ExpressionAssignment) {
				ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;
				Expression<?> dest = assignment.getDestination();
				if (dest != null) {
					for (Variable<?, ?> var : dest.getDependencies()) {
						if (var.getName() != null) {
							assignedVars.add(var.getName());
						}
					}
				}
			}
		}

		// Collect from variables (local declarations)
		for (ExpressionAssignment<?> var : scope.getVariables()) {
			Expression<?> dest = var.getDestination();
			if (dest != null) {
				for (Variable<?, ?> v : dest.getDependencies()) {
					if (v.getName() != null) {
						assignedVars.add(v.getName());
					}
				}
			}
		}

		// Recurse into child scopes
		for (Scope<?> child : scope.getChildren()) {
			collectAssignedVariablesRecursive(child, assignedVars);
		}
	}

	/**
	 * Checks if an expression is loop-invariant.
	 *
	 * <p>An expression is loop-invariant if it does not reference the loop index
	 * variable or any variable that is assigned inside the loop body.</p>
	 *
	 * @param expr the expression to check
	 * @param loopAssignedVariables names of variables assigned inside the loop
	 * @param loopIndices indices from this and nested loops
	 * @return true if the expression does not depend on any loop-variant variables
	 */
	private boolean isLoopInvariant(Expression<?> expr, Set<String> loopAssignedVariables, List<Index> loopIndices) {
		if (index == null) return false;

		// Check if expression references any loop index (Index objects, not Variables)
		for (Index idx : loopIndices) {
			if (expr.containsIndex(idx)) {
				return false;
			}
		}

		// Check all variable dependencies of the expression
		for (Variable<?, ?> var : expr.getDependencies()) {
			if (var.getName() != null && loopAssignedVariables.contains(var.getName())) {
				return false;
			}
		}

		return true;
	}

	@Override
	public Repeated<T> generate(List<Scope<T>> children) {
		Repeated<T> scope = getMetadata() == null ? new Repeated<>(getName()) : new Repeated<>(getName(), getMetadata());
		scope.setIndex(getIndex());
		scope.getChildren().addAll(children);
		return scope;
	}
}
