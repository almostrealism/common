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
import io.almostrealism.expression.Constant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.profile.OperationMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Scope} representing a repeated (loop) block of code.
 *
 * <p>{@code Repeated} generates a for-loop in the output code, iterating from 0
 * while the condition is true, incrementing by the specified interval.</p>
 *
 * <h2>Loop-Invariant Code Motion (LICM)</h2>
 * <p>During simplification, {@code Repeated} hoists loop-invariant statements
 * outside the loop body. A statement is loop-invariant if its expression does
 * not depend on the loop index variable or any variable assigned inside the loop.
 * This optimization reduces per-iteration computation cost.</p>
 *
 * <p>LICM is enabled by default. Invariant statements are moved from
 * descendant scopes to this scope's statements list, which is rendered before
 * the loop body. The hoisting recurses into all descendant scopes (including
 * grandchildren and deeper) to find invariant declarations at any nesting
 * depth.</p>
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
	 * <p>Defaults to {@code true}. This field is public to support
	 * differential testing (comparing LICM-enabled vs LICM-disabled output).
	 * Production code should not disable LICM. The invariance analysis uses a
	 * fixed-point algorithm that correctly handles:</p>
	 * <ul>
	 *   <li>Loop indices via the {@link Index} interface (using {@code containsIndex})</li>
	 *   <li>Variable dependencies via {@code getDependencies()}</li>
	 *   <li>Named Index objects like {@link io.almostrealism.kernel.DefaultIndex}
	 *       (using {@code getIndices()} to check names against assigned variables)</li>
	 *   <li>Local declaration dependency chains via {@link StaticReference} name matching
	 *       (ensures declarations referencing other loop-variant declarations are not hoisted)</li>
	 * </ul>
	 */
	public static boolean enableLoopInvariantHoisting = true;

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
		// Phase 1: collect the base set of loop-variant variable names.
		// This includes the loop index, nested loop indices, and destinations
		// of non-declaration assignments (e.g., array element writes like source[offset] = 0.0).
		Set<String> variantNames = collectBaseVariantNames(scope);

		// Collect all loop indices (this scope's index plus nested loop indices)
		List<Index> loopIndices = collectLoopIndices(scope);

		// Phase 2: collect all declarations from child scopes and propagate variance.
		// A declaration is variant if its expression references any variant name.
		// Since declarations can form dependency chains (f_1 depends on f_0), we
		// iterate until no new variant names are discovered (fixed-point).
		List<DeclarationInfo> allDeclarations = collectDeclarations(scope);
		propagateVariance(allDeclarations, variantNames, loopIndices);

		// Phase 3: hoist declarations whose names are NOT in the variant set.
		// This recurses into all descendant scopes (grandchildren, etc.) to find
		// invariant declarations at any nesting depth — not just direct children.
		List<Statement<?>> hoisted = new ArrayList<>();

		for (Scope<T> child : scope.getChildren()) {
			hoistInvariantDeclarations(child, variantNames, hoisted);
		}

		// Phase 4: extract loop-invariant sub-expressions from remaining variant
		// expressions. This handles cases like genome-only pow() sub-expressions
		// embedded within larger variant envelope accumulate expressions. Each
		// extracted sub-expression becomes a new declaration that is hoisted.
		List<Statement<?>> extractedDeclarations = new ArrayList<>();
		extractInvariantSubExpressions(scope, variantNames, loopIndices, extractedDeclarations);

		// Add all hoisted statements to this scope (before the loop).
		// Standard hoisted declarations come first, then extracted sub-expression
		// declarations (which may reference kernel arguments used by hoisted decls).
		List<Statement<?>> allHoisted = new ArrayList<>();
		allHoisted.addAll(hoisted);
		allHoisted.addAll(extractedDeclarations);
		scope.getStatements().addAll(0, allHoisted);
	}

	/**
	 * Recursively finds and removes loop-invariant declaration statements from a scope
	 * and all its descendants. Removed declarations are added to the {@code hoisted} list
	 * for placement before the loop.
	 *
	 * <p>This recursion ensures that invariant declarations in grandchild scopes (and deeper)
	 * are hoisted, not just those in direct children. This is critical for the AudioScene
	 * pipeline where {@code f_assignment} declarations live in nested scope structures.</p>
	 *
	 * @param scope the scope to scan for invariant declarations
	 * @param variantNames the set of loop-variant variable names
	 * @param hoisted the list to add hoisted statements to
	 */
	private void hoistInvariantDeclarations(Scope<?> scope, Set<String> variantNames,
											List<Statement<?>> hoisted) {
		List<Statement<?>> toRemove = new ArrayList<>();

		for (Statement<?> stmt : scope.getStatements()) {
			if (stmt instanceof ExpressionAssignment) {
				ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;

				if (assignment.isDeclaration()) {
					String declName = getDeclarationName(assignment);
					if (declName != null && !variantNames.contains(declName)) {
						hoisted.add(stmt);
						toRemove.add(stmt);
					}
				}
			}
		}

		scope.getStatements().removeAll(toRemove);

		for (Scope<?> child : scope.getChildren()) {
			hoistInvariantDeclarations(child, variantNames, hoisted);
		}
	}

	/**
	 * Extracts loop-invariant sub-expressions from variant expressions in the loop body.
	 *
	 * <p>After the standard LICM hoisting (Phase 3), variant expressions may still contain
	 * invariant sub-trees. For example, an envelope accumulate expression like:</p>
	 * <pre>
	 * result += pow((- pow(genome[offset+5], 3.0)) + 1.0, -1.0) * frameCounter
	 * </pre>
	 * <p>is loop-variant (references frameCounter), but the sub-expression
	 * {@code pow((- pow(genome[offset+5], 3.0)) + 1.0, -1.0)} is loop-invariant
	 * (only references kernel arguments at constant offsets). This method finds such
	 * sub-expressions, creates new declarations for them, replaces the sub-expressions
	 * with references to the declarations, and returns the declarations for hoisting.</p>
	 *
	 * @param scope the Repeated scope being optimized
	 * @param variantNames the set of loop-variant variable names
	 * @param loopIndices all loop indices (outer and nested)
	 * @param extractedDeclarations list to receive the new extracted declarations
	 */
	private void extractInvariantSubExpressions(Repeated<T> scope, Set<String> variantNames,
												List<Index> loopIndices,
												List<Statement<?>> extractedDeclarations) {
		Map<Expression<?>, StaticReference<?>> extracted = new HashMap<>();
		int[] extractIdx = { 0 };

		for (Scope<T> child : scope.getChildren()) {
			extractFromScope(child, variantNames, loopIndices, extracted, extractIdx, extractedDeclarations);
		}
	}

	/**
	 * Recursively scans a scope and its descendants for variant expressions containing
	 * invariant sub-trees and extracts them into declarations.
	 *
	 * @param scope the scope to scan
	 * @param variantNames the set of loop-variant variable names
	 * @param loopIndices all loop indices
	 * @param extracted map of already-extracted sub-expressions to their references (for dedup)
	 * @param extractIdx counter for generating unique declaration names
	 * @param declarations list to receive new extracted declarations
	 */
	private void extractFromScope(Scope<?> scope, Set<String> variantNames,
								  List<Index> loopIndices,
								  Map<Expression<?>, StaticReference<?>> extracted,
								  int[] extractIdx, List<Statement<?>> declarations) {
		List<Statement<?>> updatedStatements = new ArrayList<>();

		for (Statement<?> stmt : scope.getStatements()) {
			if (stmt instanceof ExpressionAssignment) {
				ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;
				Expression<?> expr = assignment.getExpression();

				// Only process variant expressions — invariant ones were already hoisted
				if (!isLoopInvariant(expr, variantNames, loopIndices)) {
					List<Expression<?>> invariantSubs = findMaximalInvariantSubExpressions(
							expr, variantNames, loopIndices);

					Expression<?> replaced = expr;
					for (Expression<?> sub : invariantSubs) {
						StaticReference<?> ref = extracted.get(sub);
						if (ref == null) {
							ref = new StaticReference<>(sub.getType(),
									"f_licm_" + extractIdx[0]++);
							extracted.put(sub, ref);
							declarations.add(new ExpressionAssignment(true, ref, sub));
						}
						replaced = replaced.replace(sub, ref);
					}

					if (replaced != expr) {
						stmt = new ExpressionAssignment(
								assignment.isDeclaration(),
								assignment.getDestination(),
								replaced);
					}
				}
			}
			updatedStatements.add(stmt);
		}

		scope.getStatements().clear();
		scope.getStatements().addAll(updatedStatements);

		// Also process variables (non-statement assignments like array element writes)
		List<ExpressionAssignment<?>> updatedVariables = new ArrayList<>();
		boolean variablesChanged = false;

		for (ExpressionAssignment<?> var : scope.getVariables()) {
			Expression<?> expr = var.getExpression();
			if (expr != null && !isLoopInvariant(expr, variantNames, loopIndices)) {
				List<Expression<?>> invariantSubs = findMaximalInvariantSubExpressions(
						expr, variantNames, loopIndices);

				Expression<?> replaced = expr;
				for (Expression<?> sub : invariantSubs) {
					StaticReference<?> ref = extracted.get(sub);
					if (ref == null) {
						ref = new StaticReference<>(sub.getType(),
								"f_licm_" + extractIdx[0]++);
						extracted.put(sub, ref);
						declarations.add(new ExpressionAssignment(true, ref, sub));
					}
					replaced = replaced.replace(sub, ref);
				}

				if (replaced != expr) {
					updatedVariables.add(new ExpressionAssignment(
							var.isDeclaration(), var.getDestination(), replaced));
					variablesChanged = true;
				} else {
					updatedVariables.add(var);
				}
			} else {
				updatedVariables.add(var);
			}
		}

		if (variablesChanged) {
			scope.getVariables().clear();
			scope.getVariables().addAll(updatedVariables);
		}

		for (Scope<?> child : scope.getChildren()) {
			extractFromScope(child, variantNames, loopIndices, extracted, extractIdx, declarations);
		}
	}

	/**
	 * Finds maximal loop-invariant sub-expression trees within a variant expression.
	 *
	 * <p>A "maximal invariant sub-tree" is a sub-expression whose root is loop-invariant
	 * but whose parent in the expression tree is loop-variant. This finds the largest
	 * chunks of invariant computation that can be extracted.</p>
	 *
	 * <p>Only "substantial" sub-expressions are returned — constants and simple references
	 * are not worth extracting since they are already cheap to evaluate.</p>
	 *
	 * @param expr the expression tree to search (assumed to be variant at the root)
	 * @param variantNames the set of loop-variant variable names
	 * @param loopIndices all loop indices
	 * @return list of maximal invariant sub-expressions suitable for extraction
	 */
	private List<Expression<?>> findMaximalInvariantSubExpressions(Expression<?> expr,
																   Set<String> variantNames,
																   List<Index> loopIndices) {
		List<Expression<?>> results = new ArrayList<>();

		for (Expression<?> child : expr.getChildren()) {
			if (isLoopInvariant(child, variantNames, loopIndices)) {
				// This child is invariant — it's a maximal invariant sub-tree.
				// Only extract if it's substantial (not a constant or simple reference).
				if (isSubstantialForExtraction(child)) {
					results.add(child);
				}
			} else {
				// This child is variant — recurse to find invariant sub-trees within it
				results.addAll(findMaximalInvariantSubExpressions(child, variantNames, loopIndices));
			}
		}

		return results;
	}

	/**
	 * Determines whether a loop-invariant sub-expression is substantial enough to
	 * warrant extraction into a separate declaration.
	 *
	 * <p>Constants and simple static references are not worth extracting since they
	 * add a declaration overhead without meaningful reduction in per-iteration
	 * computation. Any expression with at least one child (treeDepth >= 1)
	 * represents a real computation (e.g., {@code pow(x, 3.0)}) and is worth
	 * extracting from a loop body.</p>
	 *
	 * @param expr the invariant expression to evaluate
	 * @return true if the expression should be extracted into a declaration
	 */
	private boolean isSubstantialForExtraction(Expression<?> expr) {
		if (expr instanceof Constant) return false;
		if (expr instanceof StaticReference) return false;
		return expr.treeDepth() >= 1;
	}

	/**
	 * Collects the base set of loop-variant variable names, excluding declaration names.
	 *
	 * <p>This includes the loop index variable, nested loop indices, and the destinations
	 * of non-declaration assignments (array element writes, etc.). Declaration names are
	 * handled separately via variance propagation in {@link #propagateVariance}.</p>
	 *
	 * @param scope the repeated scope to analyze
	 * @return a set of variable names that are inherently loop-variant
	 */
	private Set<String> collectBaseVariantNames(Repeated<T> scope) {
		Set<String> variantNames = new HashSet<>();

		// The loop index itself is loop-variant
		Variable<Integer, ?> scopeIndex = scope.getIndex();
		if (scopeIndex != null && scopeIndex.getName() != null) {
			variantNames.add(scopeIndex.getName());
		}

		// Collect non-declaration assignment targets and nested loop indices
		for (Scope<T> child : scope.getChildren()) {
			collectBaseVariantNamesRecursive(child, variantNames);
		}

		return variantNames;
	}

	/**
	 * Recursively collects non-declaration assignment targets and nested loop indices.
	 *
	 * @param scope the scope to analyze
	 * @param variantNames the set to add variant names to
	 */
	private void collectBaseVariantNamesRecursive(Scope<?> scope, Set<String> variantNames) {
		if (scope instanceof Repeated) {
			Repeated<?> nested = (Repeated<?>) scope;
			Variable<Integer, ?> nestedIndex = nested.getIndex();
			if (nestedIndex != null && nestedIndex.getName() != null) {
				variantNames.add(nestedIndex.getName());
			}
		}

		for (Statement<?> stmt : scope.getStatements()) {
			if (stmt instanceof ExpressionAssignment) {
				ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;
				if (!assignment.isDeclaration()) {
					// Non-declaration assignments (e.g., source[offset] = 0.0) are
					// inherently loop-variant because they mutate state each iteration
					Expression<?> dest = assignment.getDestination();
					if (dest != null) {
						// Collect the destination name directly if it is a StaticReference,
						// since StaticReference.getDependencies() may return empty
						if (dest instanceof StaticReference) {
							String name = ((StaticReference<?>) dest).getName();
							if (name != null) {
								variantNames.add(name);
							}
						}

						for (Variable<?, ?> var : dest.getDependencies()) {
							if (var.getName() != null) {
								variantNames.add(var.getName());
							}
						}
					}
				}
			}
		}

		for (ExpressionAssignment<?> var : scope.getVariables()) {
			Expression<?> dest = var.getDestination();
			if (dest != null) {
				// Collect the destination name directly if it is a StaticReference,
				// since StaticReference.getDependencies() may return empty
				if (dest instanceof StaticReference) {
					String name = ((StaticReference<?>) dest).getName();
					if (name != null) {
						variantNames.add(name);
					}
				}

				for (Variable<?, ?> v : dest.getDependencies()) {
					if (v.getName() != null) {
						variantNames.add(v.getName());
					}
				}
			}
		}

		for (Scope<?> child : scope.getChildren()) {
			collectBaseVariantNamesRecursive(child, variantNames);
		}
	}

	/**
	 * Collects all declaration statements from child scopes of the given Repeated scope.
	 *
	 * @param scope the repeated scope whose children to scan
	 * @return a list of declaration info records
	 */
	private List<DeclarationInfo> collectDeclarations(Repeated<T> scope) {
		List<DeclarationInfo> declarations = new ArrayList<>();
		for (Scope<T> child : scope.getChildren()) {
			collectDeclarationsRecursive(child, declarations);
		}
		return declarations;
	}

	/**
	 * Recursively collects declaration statements from a scope and its children.
	 *
	 * @param scope the scope to scan
	 * @param declarations the list to add declarations to
	 */
	private void collectDeclarationsRecursive(Scope<?> scope, List<DeclarationInfo> declarations) {
		for (Statement<?> stmt : scope.getStatements()) {
			if (stmt instanceof ExpressionAssignment) {
				ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;
				if (assignment.isDeclaration()) {
					String name = getDeclarationName(assignment);
					if (name != null) {
						declarations.add(new DeclarationInfo(name, assignment.getExpression()));
					}
				}
			}
		}
		for (Scope<?> child : scope.getChildren()) {
			collectDeclarationsRecursive(child, declarations);
		}
	}

	/**
	 * Propagates variance through declaration dependency chains using a fixed-point algorithm.
	 *
	 * <p>A declaration is loop-variant if its expression references any variant name (through
	 * any mechanism: Index objects, Variable dependencies, StaticReference names, or named Index
	 * objects). When a declaration is found to be variant, its name is added to the variant set,
	 * which may cause other declarations that reference it to become variant as well.</p>
	 *
	 * @param declarations all declarations in the loop body
	 * @param variantNames the set of variant names (modified in place)
	 * @param loopIndices indices from this and nested loops
	 */
	private void propagateVariance(List<DeclarationInfo> declarations, Set<String> variantNames, List<Index> loopIndices) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (DeclarationInfo decl : declarations) {
				if (!variantNames.contains(decl.name)) {
					if (!isLoopInvariant(decl.expression, variantNames, loopIndices)) {
						variantNames.add(decl.name);
						changed = true;
					}
				}
			}
		}
	}

	/**
	 * Extracts the variable name from a declaration's destination expression.
	 *
	 * @param assignment the declaration assignment
	 * @return the declared variable name, or null if it cannot be determined
	 */
	private String getDeclarationName(ExpressionAssignment<?> assignment) {
		Expression<?> dest = assignment.getDestination();
		if (dest instanceof StaticReference) {
			return ((StaticReference<?>) dest).getName();
		}
		return null;
	}

	/**
	 * Simple record holding a declaration's variable name and its initializer expression.
	 */
	private static class DeclarationInfo {
		final String name;
		final Expression<?> expression;

		DeclarationInfo(String name, Expression<?> expression) {
			this.name = name;
			this.expression = expression;
		}
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
	 * Checks if an expression is loop-invariant.
	 *
	 * <p>An expression is loop-invariant if it does not reference the loop index
	 * variable or any variable that is assigned inside the loop body.</p>
	 *
	 * <p>This check handles multiple ways that loop variables can be referenced:</p>
	 * <ul>
	 *   <li>Via {@link Index} objects (checked via {@code containsIndex})</li>
	 *   <li>Via {@link Variable} dependencies (checked via {@code getDependencies})</li>
	 *   <li>Via named Index objects like {@link io.almostrealism.kernel.DefaultIndex}
	 *       that may not be in the loopIndices list but reference the same variable
	 *       name (checked via {@code getIndices})</li>
	 *   <li>Via {@link StaticReference} nodes whose name matches a loop-assigned variable
	 *       (handles local declarations like {@code double f_0 = ...} that are referenced
	 *       by subsequent expressions via {@code StaticReference("f_0")})</li>
	 * </ul>
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

		// Check all Index objects in the expression by name.
		// This handles the case where the Repeated scope's index variable is a plain Variable,
		// but expressions use a separate Index object (like DefaultIndex) with the same name.
		// The Index objects won't be in loopIndices (since collectLoopIndices only looks at
		// scope.getIndex()), but we can check their names against loopAssignedVariables.
		for (Index idx : expr.getIndices()) {
			if (idx.getName() != null && loopAssignedVariables.contains(idx.getName())) {
				return false;
			}
		}

		// Check for StaticReference nodes that reference loop-assigned variables by name.
		// This handles the case where a declaration like "double f_0 = expr" creates a
		// StaticReference("f_0") that is used in subsequent expressions. Without this check,
		// expressions referencing f_0 could be incorrectly hoisted because StaticReference
		// without a referent returns empty from getDependencies().
		if (containsStaticReferenceToAny(expr, loopAssignedVariables)) {
			return false;
		}

		return true;
	}

	/**
	 * Recursively checks whether an expression tree contains any {@link StaticReference}
	 * whose name matches one of the given variable names.
	 *
	 * @param expr the expression tree to search
	 * @param names the set of variable names to look for
	 * @return true if a matching {@link StaticReference} is found
	 */
	private boolean containsStaticReferenceToAny(Expression<?> expr, Set<String> names) {
		if (expr instanceof StaticReference) {
			String name = ((StaticReference<?>) expr).getName();
			if (name != null && names.contains(name)) {
				return true;
			}
		}

		for (Expression<?> child : expr.getChildren()) {
			if (containsStaticReferenceToAny(child, names)) {
				return true;
			}
		}

		return false;
	}

	@Override
	public Repeated<T> generate(List<Scope<T>> children) {
		Repeated<T> scope = getMetadata() == null ? new Repeated<>(getName()) : new Repeated<>(getName(), getMetadata());
		scope.setIndex(getIndex());
		scope.getChildren().addAll(children);
		return scope;
	}
}
