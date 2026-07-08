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
import io.almostrealism.expression.Constant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.sequence.Index;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

	/**
	 * Controls whether LICM diagnostic logging is enabled.
	 * When true, detailed information about variance classification is printed
	 * to stderr for Repeated scopes with more than 10 declarations.
	 * Can also be enabled via the system property {@code AR_LICM_DIAGNOSTICS=enabled}.
	 */
	public static boolean enableLicmDiagnostics =
			SystemUtils.isEnabled("AR_LICM_DIAGNOSTICS").orElse(false);

	/**
	 * Controls whether accumulator promotion is enabled.
	 * When true, loop-body statements that store to an array element whose
	 * position does not change across iterations (a reduction pattern like
	 * {@code out[i] = out[i] + in[j]}) are rewritten to accumulate in a local
	 * variable, with a single store to the array element after the loop. This
	 * removes a global-memory read-modify-write from every iteration, which
	 * compilers for some backends (notably OpenCL) cannot do themselves because
	 * unqualified global pointers may alias.
	 *
	 * <p>Defaults to {@code true}. This field is public to support differential
	 * testing (comparing promotion-enabled vs promotion-disabled output).
	 * Production code should not disable promotion.</p>
	 *
	 * <p>The transform assumes distinct kernel argument arrays do not overlap in
	 * memory, which is the same assumption made throughout scope compilation. It
	 * is conservative in every other respect: it bails out on nested loops,
	 * method calls, metrics, non-assignment statements, and any reference to the
	 * promoted array that is not structurally identical to the promoted element.</p>
	 */
	public static boolean enableAccumulatorPromotion = true;

	/** The variable incremented on each loop iteration and used as the loop index. */
	private Variable<Integer, ?> index;

	/** The expression by which the index is incremented each iteration; defaults to 1. */
	private Expression<Integer> interval;

	/** The boolean guard expression evaluated before each iteration; the loop continues while true. */
	private Expression<Boolean> condition;

	/** Statements rendered after the loop closes (e.g. final accumulator stores). */
	private List<Statement<?>> epilogue = new ArrayList<>();

	/**
	 * Creates an empty {@link Repeated} scope with no index, condition, or interval set.
	 */
	public Repeated() { }

	/**
	 * Creates an empty {@link Repeated} scope with the given name.
	 *
	 * @param name the function name for this scope
	 */
	public Repeated(String name) {
		this();
		setName(name);
	}

	/**
	 * Creates a {@link Repeated} scope with the given name and operation metadata.
	 *
	 * @param name     the function name for this scope
	 * @param metadata the operation metadata; must not be {@code null}
	 * @throws IllegalArgumentException if {@code metadata} is {@code null}
	 */
	public Repeated(String name, OperationMetadata metadata) {
		this(name);
		if (metadata == null)
			throw new IllegalArgumentException();
		setMetadata(new OperationMetadata(metadata));
	}

	/**
	 * Creates a {@link Repeated} scope that increments the index by 1 each iteration.
	 *
	 * @param idx       the loop index variable
	 * @param condition the loop continuation condition
	 */
	public Repeated(Variable<Integer, ?> idx, Expression<Boolean> condition) {
		this(idx, condition, new IntegerConstant(1));
	}

	/**
	 * Creates a {@link Repeated} scope with a custom increment interval.
	 *
	 * @param idx       the loop index variable
	 * @param condition the loop continuation condition
	 * @param interval  the increment applied to the index at the end of each iteration
	 */
	public Repeated(Variable<Integer, ?> idx, Expression<Boolean> condition, Expression<Integer> interval) {
		this();
		setIndex(idx);
		setInterval(interval);
		setCondition(condition);
	}

	/**
	 * Returns the loop index variable for this scope.
	 *
	 * @return the loop index variable
	 */
	public Variable<Integer, ?> getIndex() { return index; }

	/**
	 * Sets the loop index variable for this scope.
	 *
	 * @param index the loop index variable
	 */
	public void setIndex(Variable<Integer, ?> index) { this.index = index; }

	/**
	 * Returns the interval by which the loop index is incremented each iteration.
	 *
	 * @return the increment interval expression
	 */
	public Expression<Integer> getInterval() { return interval; }

	/**
	 * Sets the interval by which the loop index is incremented each iteration.
	 *
	 * @param interval the increment interval expression
	 */
	public void setInterval(Expression<Integer> interval) { this.interval = interval; }

	/**
	 * Returns the boolean condition expression that controls loop continuation.
	 *
	 * @return the loop condition
	 */
	public Expression<Boolean> getCondition() { return condition; }

	/**
	 * Sets the boolean condition expression that controls loop continuation.
	 *
	 * @param condition the loop condition
	 */
	public void setCondition(Expression<Boolean> condition) { this.condition = condition; }

	/**
	 * Returns the statements rendered after the loop closes, such as the final
	 * accumulator stores produced by accumulator promotion.
	 *
	 * @return the epilogue statements
	 */
	public List<Statement<?>> getEpilogue() { return epilogue; }

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

		for (Statement s : getEpilogue()) { w.println(s); }
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

		if (enableAccumulatorPromotion && index != null && index.getName() != null) {
			promoteAccumulators(scope);
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
		List<ExpressionAssignment<?>> allDeclarations = new ArrayList<>();
		for (Scope<T> child : scope.getChildren()) {
			allDeclarations.addAll(child.collectDeclarations());
		}

		propagateVariance(allDeclarations, variantNames, loopIndices);

		// Phase 3: hoist declarations whose names are NOT in the variant set.
		List<Statement<?>> hoisted = new ArrayList<>();

		for (Scope<T> child : scope.getChildren()) {
			hoistInvariantDeclarations(child, variantNames, hoisted);
		}

		// Phase 4: extract loop-invariant sub-expressions from remaining variant
		// declaration expressions.
		List<Statement<?>> extractedDeclarations = new ArrayList<>();
		extractInvariantSubExpressions(scope, variantNames, loopIndices, extractedDeclarations);

		if (enableLicmDiagnostics && allDeclarations.size() > 10) {
			log(scope.getName() + ": " +
					allDeclarations.size() + " decls, " +
					hoisted.size() + " hoisted, " +
					extractedDeclarations.size() + " extracted");
		}

		// Add all hoisted statements to this scope (before the loop).
		// Standard hoisted declarations come first, then extracted sub-expression
		// declarations (which may reference kernel arguments used by hoisted decls).
		List<Statement<?>> allHoisted = new ArrayList<>();
		allHoisted.addAll(hoisted);
		allHoisted.addAll(extractedDeclarations);
		scope.getStatements().addAll(0, allHoisted);
	}

	/**
	 * Identifies reduction-style statements in the loop body and promotes their
	 * array-element accumulators to local variables.
	 *
	 * <p>A statement like {@code out[idx] = out[idx] + in[i]} where {@code idx}
	 * does not change across iterations performs a global-memory read and write on
	 * every iteration. This method rewrites the pattern to accumulate in a local
	 * variable instead:</p>
	 * <pre>
	 * double acc = out[idx];          // declared before the loop
	 * for (...) { acc = acc + in[i]; }
	 * out[idx] = acc;                 // stored once, after the loop
	 * </pre>
	 *
	 * <p>Promotion of an array is abandoned unless every reference to that array
	 * in the loop body (read or write, in any statement) is structurally identical
	 * to one of the promoted element references, and the loop condition and
	 * interval do not reference the array at all. Pre-loop statements are left
	 * untouched: the local is initialized from a load of the element, so any
	 * initialization store that precedes the loop is still observed.</p>
	 *
	 * @param scope the simplified scope to optimize
	 */
	private void promoteAccumulators(Repeated<T> scope) {
		List<ExpressionAssignment<?>> body = new ArrayList<>();
		for (Scope<T> child : scope.getChildren()) {
			if (!collectBodyAssignments(child, body)) return;
		}
		if (body.isEmpty()) return;

		Set<String> scalarNames = new HashSet<>();
		scalarNames.add(scope.getIndex().getName());

		Set<String> storedArrays = new HashSet<>();

		for (ExpressionAssignment<?> assignment : body) {
			Expression<?> dest = assignment.getDestination();

			if (dest instanceof StaticReference) {
				String name = ((StaticReference<?>) dest).getName();
				if (name != null) scalarNames.add(name);
			} else if (dest instanceof InstanceReference) {
				Variable<?, ?> referent = ((InstanceReference<?, ?>) dest).getReferent();
				if (referent == null || referent.getName() == null) return;
				if (!assignment.isDeclaration()) storedArrays.add(referent.getName());
			} else if (dest != null) {
				// A destination of an unrecognized form may mutate anything it
				// references on each iteration, so every name it mentions must
				// be treated as loop-variant
				dest.collectReferencedNames(scalarNames);
			}
		}

		if (storedArrays.isEmpty()) return;

		Map<String, List<InstanceReference<?, ?>>> candidates = new LinkedHashMap<>();
		Set<String> disqualified = new HashSet<>();

		for (ExpressionAssignment<?> assignment : body) {
			Expression<?> dest = assignment.getDestination();
			if (assignment.isDeclaration() || !(dest instanceof InstanceReference)) continue;

			InstanceReference<?, ?> ref = (InstanceReference<?, ?>) dest;
			String name = ref.getReferent().getName();
			Expression<?> pos = ref.getChildren().isEmpty() ? null : ref.getChildren().get(0);

			if (pos == null || !isIterationInvariant(pos, scalarNames, storedArrays)) {
				disqualified.add(name);
				continue;
			}

			List<InstanceReference<?, ?>> refs = candidates.computeIfAbsent(name, k -> new ArrayList<>());
			if (refs.stream().noneMatch(c -> matchesReference(c, ref))) refs.add(ref);
		}

		int total = 0;

		for (Map.Entry<String, List<InstanceReference<?, ?>>> entry : candidates.entrySet()) {
			String arrayName = entry.getKey();
			if (disqualified.contains(arrayName)) continue;

			Set<String> nameSet = Collections.singleton(arrayName);
			if (scope.getCondition().containsInstanceReferenceToAny(nameSet)) continue;
			if (scope.getInterval().containsInstanceReferenceToAny(nameSet)) continue;

			List<InstanceReference<?, ?>> dests = entry.getValue();
			if (!isPromotableEverywhere(body, arrayName, nameSet, dests)) continue;

			List<StaticReference<?>> accumulators =
					createAccumulators(dests, scope.getIndex().getName(), total);
			if (accumulators == null) continue;
			total += accumulators.size();

			for (int i = 0; i < dests.size(); i++) {
				scope.getStatements().add(new ExpressionAssignment(true, accumulators.get(i), dests.get(i)));
				scope.getEpilogue().add(new ExpressionAssignment(dests.get(i), accumulators.get(i)));
			}

			for (Scope<T> child : scope.getChildren()) {
				rewriteBodyAssignments(child, arrayName, dests, accumulators);
			}
		}
	}

	/**
	 * Recursively collects every {@link ExpressionAssignment} from a loop-body scope
	 * and its descendants, failing when the body contains anything whose memory
	 * behavior cannot be fully analyzed.
	 *
	 * <p>Only plain {@link Scope} instances are analyzable: subclasses carry
	 * expressions outside the statements list that read memory invisibly to this
	 * analysis ({@link Cases} branch conditions, {@link HybridScope} explicit code,
	 * nested {@link Repeated} loop bounds), so any of them causes analysis to fail.
	 * Method calls, metrics, and statements that are not
	 * {@link ExpressionAssignment}s fail for the same reason, which makes the
	 * caller skip accumulator promotion for the whole loop.</p>
	 *
	 * @param scope the loop-body scope to scan
	 * @param assignments the list to add assignments to
	 * @return true if the body was fully analyzable, false otherwise
	 */
	private static boolean collectBodyAssignments(Scope<?> scope, List<ExpressionAssignment<?>> assignments) {
		if (scope.getClass() != Scope.class) return false;
		if (!scope.getMethods().isEmpty()) return false;
		if (!scope.getMetrics().isEmpty()) return false;

		for (Statement<?> stmt : scope.getStatements()) {
			if (!(stmt instanceof ExpressionAssignment)) return false;
			assignments.add((ExpressionAssignment<?>) stmt);
		}

		assignments.addAll(scope.getVariables());

		for (Scope<?> child : scope.getChildren()) {
			if (!collectBodyAssignments(child, assignments)) return false;
		}

		return true;
	}

	/**
	 * Checks whether an expression produces the same value on every iteration of
	 * the loop.
	 *
	 * <p>Unlike {@link #isLoopInvariant(Expression, Set, List)}, which treats any
	 * dependency on a stored array as variant, this method permits size and offset
	 * references to stored arrays (which never change during a kernel) while still
	 * rejecting element reads of stored arrays, references to scalars assigned in
	 * the loop, and references to the loop index.</p>
	 *
	 * @param expr the expression to check
	 * @param scalarNames names of the loop index and scalars assigned in the loop
	 * @param storedArrays names of arrays stored to inside the loop
	 * @return true if the expression's value cannot change across iterations
	 */
	private static boolean isIterationInvariant(Expression<?> expr, Set<String> scalarNames, Set<String> storedArrays) {
		if (expr.containsInstanceReferenceToAny(storedArrays)) return false;
		if (expr.containsStaticReferenceToAny(scalarNames)) return false;

		for (Index idx : expr.getIndices()) {
			if (idx.getName() != null && scalarNames.contains(idx.getName())) return false;
		}

		for (Variable<?, ?> var : expr.getDependencies()) {
			if (var.getName() != null && scalarNames.contains(var.getName())) return false;
		}

		return true;
	}

	/**
	 * Checks whether two array-element references address the same element:
	 * the same referent variable (by name) at a structurally equal position.
	 *
	 * @param a the first reference
	 * @param b the second reference
	 * @return true if both references address the same array element
	 */
	private static boolean matchesReference(InstanceReference<?, ?> a, InstanceReference<?, ?> b) {
		Variable<?, ?> ra = a.getReferent();
		Variable<?, ?> rb = b.getReferent();
		if (ra == null || rb == null || ra.getName() == null || !ra.getName().equals(rb.getName()))
			return false;

		Expression<?> pa = a.getChildren().isEmpty() ? null : a.getChildren().get(0);
		Expression<?> pb = b.getChildren().isEmpty() ? null : b.getChildren().get(0);
		return pa != null && Objects.equals(pa, pb);
	}

	/**
	 * Verifies that every reference to the given array across all loop-body
	 * assignments addresses one of the promoted elements, and that no assignment
	 * with an unanalyzable destination touches the array.
	 *
	 * @param body all assignments in the loop body
	 * @param arrayName the array being considered for promotion
	 * @param nameSet a singleton set containing {@code arrayName}
	 * @param dests the promoted element references
	 * @return true if promotion of the array preserves the loop's semantics
	 */
	private static boolean isPromotableEverywhere(List<ExpressionAssignment<?>> body, String arrayName,
												  Set<String> nameSet, List<InstanceReference<?, ?>> dests) {
		for (ExpressionAssignment<?> assignment : body) {
			Expression<?> dest = assignment.getDestination();

			if (dest != null) {
				if (!(dest instanceof InstanceReference) && !(dest instanceof StaticReference)
						&& dest.containsInstanceReferenceToAny(nameSet)) {
					return false;
				}

				if (!referencesMatchDestinations(dest, arrayName, dests)) return false;
			}

			if (!referencesMatchDestinations(assignment.getExpression(), arrayName, dests)) return false;
		}

		return true;
	}

	/**
	 * Checks that every {@link InstanceReference} to the named array within an
	 * expression tree addresses one of the given element references.
	 *
	 * @param expr the expression tree to scan
	 * @param arrayName the array name to look for
	 * @param dests the sanctioned element references
	 * @return true if no reference to the array addresses any other element
	 */
	private static boolean referencesMatchDestinations(Expression<?> expr, String arrayName,
													   List<InstanceReference<?, ?>> dests) {
		if (expr instanceof InstanceReference) {
			InstanceReference<?, ?> ref = (InstanceReference<?, ?>) expr;
			Variable<?, ?> referent = ref.getReferent();
			if (referent != null && arrayName.equals(referent.getName())
					&& dests.stream().noneMatch(d -> matchesReference(d, ref))) {
				return false;
			}
		}

		for (Expression<?> child : expr.getChildren()) {
			if (!referencesMatchDestinations(child, arrayName, dests)) return false;
		}

		return true;
	}

	/**
	 * Creates one local accumulator reference per promoted element, using the
	 * loop index name as a uniqueness prefix.
	 *
	 * @param dests the promoted element references
	 * @param prefix the loop index name used to make accumulator names unique
	 * @param start the starting counter value for accumulator naming
	 * @return the accumulator references, or null if any element type is unavailable
	 */
	private static List<StaticReference<?>> createAccumulators(List<InstanceReference<?, ?>> dests,
															   String prefix, int start) {
		List<StaticReference<?>> accumulators = new ArrayList<>();

		for (InstanceReference<?, ?> d : dests) {
			Class type = d.getType();
			if (type == null) return null;
			accumulators.add(new StaticReference<>(type, prefix + "_acc" + (start + accumulators.size())));
		}

		return accumulators;
	}

	/**
	 * Recursively rewrites all assignments in a loop-body scope, replacing each
	 * reference to a promoted array element with its local accumulator.
	 *
	 * @param scope the loop-body scope to rewrite
	 * @param arrayName the promoted array
	 * @param dests the promoted element references
	 * @param accumulators the local accumulator for each promoted element
	 */
	private static void rewriteBodyAssignments(Scope<?> scope, String arrayName,
											   List<InstanceReference<?, ?>> dests,
											   List<StaticReference<?>> accumulators) {
		List<Statement<?>> statements = scope.getStatements();
		for (int i = 0; i < statements.size(); i++) {
			if (statements.get(i) instanceof ExpressionAssignment) {
				statements.set(i, rewriteAssignment(
						(ExpressionAssignment<?>) statements.get(i), arrayName, dests, accumulators));
			}
		}

		List<ExpressionAssignment<?>> variables = scope.getVariables();
		for (int i = 0; i < variables.size(); i++) {
			variables.set(i, rewriteAssignment(variables.get(i), arrayName, dests, accumulators));
		}

		for (Scope<?> child : scope.getChildren()) {
			rewriteBodyAssignments(child, arrayName, dests, accumulators);
		}
	}

	/**
	 * Rewrites a single assignment, replacing references to promoted array elements
	 * with their local accumulators in both the destination and the expression.
	 * Returns the original assignment if nothing was replaced.
	 *
	 * @param assignment the assignment to rewrite
	 * @param arrayName the promoted array
	 * @param dests the promoted element references
	 * @param accumulators the local accumulator for each promoted element
	 * @return a new assignment with replacements, or the original if unchanged
	 */
	private static ExpressionAssignment<?> rewriteAssignment(ExpressionAssignment<?> assignment, String arrayName,
															 List<InstanceReference<?, ?>> dests,
															 List<StaticReference<?>> accumulators) {
		Expression<?> dest = assignment.getDestination();
		Expression<?> expr = assignment.getExpression();

		Expression<?> newDest = dest == null ? null : substituteAccumulators(dest, arrayName, dests, accumulators);
		Expression<?> newExpr = substituteAccumulators(expr, arrayName, dests, accumulators);

		if (newDest == dest && newExpr == expr) return assignment;
		return new ExpressionAssignment(assignment.isDeclaration(), newDest, newExpr);
	}

	/**
	 * Replaces every reference to a promoted array element within an expression
	 * tree with its local accumulator, using position-based child replacement.
	 * Returns the original expression if no replacements were made.
	 *
	 * @param expr the expression to process
	 * @param arrayName the promoted array
	 * @param dests the promoted element references
	 * @param accumulators the local accumulator for each promoted element
	 * @return the expression with replacements applied, or the original if unchanged
	 */
	private static Expression<?> substituteAccumulators(Expression<?> expr, String arrayName,
														List<InstanceReference<?, ?>> dests,
														List<StaticReference<?>> accumulators) {
		if (expr instanceof InstanceReference) {
			InstanceReference<?, ?> ref = (InstanceReference<?, ?>) expr;
			Variable<?, ?> referent = ref.getReferent();
			if (referent != null && arrayName.equals(referent.getName())) {
				for (int i = 0; i < dests.size(); i++) {
					if (matchesReference(dests.get(i), ref)) return accumulators.get(i);
				}
			}
		}

		List<Expression<?>> children = expr.getChildren();
		if (children.isEmpty()) return expr;

		List<Expression<?>> updated = new ArrayList<>(children);
		boolean changed = false;

		for (int i = 0; i < children.size(); i++) {
			Expression<?> replaced = substituteAccumulators(children.get(i), arrayName, dests, accumulators);
			if (replaced != children.get(i)) {
				updated.set(i, replaced);
				changed = true;
			}
		}

		return changed ? expr.generate(updated) : expr;
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
	private static void hoistInvariantDeclarations(Scope<?> scope, Set<String> variantNames,
												List<Statement<?>> hoisted) {
		List<Statement<?>> toRemove = new ArrayList<>();

		for (Statement<?> stmt : scope.getStatements()) {
			if (stmt instanceof ExpressionAssignment) {
				ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;

				if (assignment.isDeclaration()) {
					String declName = Scope.getDeclarationName(assignment);
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
	 * Extracts loop-invariant sub-expressions from variant declaration expressions
	 * in the loop body.
	 *
	 * <p>After the standard LICM hoisting (Phase 3), variant declarations may still
	 * contain invariant sub-trees. For example, an envelope accumulate expression like:</p>
	 * <pre>
	 * double f_result = pow((- pow(genome[offset+5], 3.0)) + 1.0, -1.0) * frameCounter
	 * </pre>
	 * <p>is loop-variant (references frameCounter), but the sub-expression
	 * {@code pow((- pow(genome[offset+5], 3.0)) + 1.0, -1.0)} is loop-invariant
	 * (only references kernel arguments at constant offsets). This method finds such
	 * sub-expressions, creates new declarations for them, replaces the sub-expressions
	 * with references to the declarations, and returns the declarations for hoisting.</p>
	 *
	 * <p>Both declaration and non-declaration assignments are processed, as well as
	 * scope variables. Declaration assignments use a lower extraction depth threshold
	 * ({@code minDepth = 1}) while non-declaration assignments use a higher threshold
	 * ({@code minDepth = 3}) to avoid extracting trivially cheap sub-expressions.</p>
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
	 * Recursively scans a scope and its descendants for variant assignment expressions
	 * containing invariant sub-trees and extracts them into declarations.
	 *
	 * <p>Both declaration assignments and non-declaration assignments (array element writes)
	 * are processed. Non-declaration assignments like envelope accumulate expressions contain
	 * genome-only sub-expressions (e.g., {@code pow((- pow(genome[offset+1], 3.0)) + 1.0, -1.0)})
	 * that are loop-invariant and can be extracted into hoisted declarations.</p>
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
		boolean statementsChanged = false;

		for (Statement<?> stmt : scope.getStatements()) {
			if (stmt instanceof ExpressionAssignment) {
				ExpressionAssignment<?> assignment = (ExpressionAssignment<?>) stmt;
				ExpressionAssignment<?> result = extractSubExpressionsFromAssignment(
						assignment, variantNames, loopIndices, extracted, extractIdx, declarations);
				updatedStatements.add(result);
				if (result != assignment) statementsChanged = true;
			} else {
				updatedStatements.add(stmt);
			}
		}

		if (statementsChanged) {
			scope.getStatements().clear();
			scope.getStatements().addAll(updatedStatements);
		}

		// Also process the deprecated variables list which may contain assignments
		List<ExpressionAssignment<?>> updatedVariables = new ArrayList<>();
		boolean variablesChanged = false;

		for (ExpressionAssignment<?> var : scope.getVariables()) {
			ExpressionAssignment<?> result = extractSubExpressionsFromAssignment(
					var, variantNames, loopIndices, extracted, extractIdx, declarations);
			updatedVariables.add(result);
			if (result != var) variablesChanged = true;
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
	 * Replaces loop-invariant sub-expressions within an assignment's expression.
	 * Returns the original assignment if no replacements were made.
	 *
	 * <p>Works for both declaration assignments (e.g., {@code double f_0 = expr}) and
	 * non-declaration assignments (e.g., {@code array[offset] = expr}). Only the
	 * right-hand side expression is processed; the destination is left unchanged.</p>
	 *
	 * <p>The extraction depth threshold differs by assignment type: declarations use a
	 * lower threshold ({@code treeDepth >= 1}) since they typically appear in moderate
	 * numbers, while non-declaration assignments (e.g., envelope accumulate lines) use
	 * a higher threshold ({@code treeDepth >= 3}) to avoid extracting trivially cheap
	 * sub-expressions that would cause code blowup.</p>
	 *
	 * @param assignment the assignment to process
	 * @param variantNames the set of loop-variant variable names
	 * @param loopIndices all loop indices
	 * @param extracted map of already-extracted sub-expressions (for dedup)
	 * @param extractIdx counter for generating unique declaration names
	 * @param declarations list to receive new extracted declarations
	 * @return a new assignment with replacements, or the original if unchanged
	 */
	private ExpressionAssignment<?> extractSubExpressionsFromAssignment(
			ExpressionAssignment<?> assignment, Set<String> variantNames,
			List<Index> loopIndices, Map<Expression<?>, StaticReference<?>> extracted,
			int[] extractIdx, List<Statement<?>> declarations) {
		Expression<?> expr = assignment.getExpression();
		if (expr != null && !isLoopInvariant(expr, variantNames, loopIndices)) {
			int minDepth = assignment.isDeclaration() ? 1 : 3;
			Expression<?> replaced = replaceInvariantSubExpressions(
					expr, variantNames, loopIndices, extracted, extractIdx, declarations, minDepth);
			if (replaced != expr) {
				return new ExpressionAssignment(
						assignment.isDeclaration(), assignment.getDestination(), replaced);
			}
		}
		return assignment;
	}

	/**
	 * Replaces maximal loop-invariant sub-expressions within a variant expression using
	 * position-based child replacement instead of {@link Expression#replace}.
	 *
	 * <p>For each child of the expression:</p>
	 * <ul>
	 *   <li>If the child is loop-invariant and substantial, it is extracted into a new
	 *       {@code f_licm_*} declaration and replaced with a reference.</li>
	 *   <li>If the child is loop-variant, this method recurses into it to find deeper
	 *       invariant sub-trees.</li>
	 *   <li>If the child is invariant but trivial (constant or simple reference), it is
	 *       left in place.</li>
	 * </ul>
	 *
	 * <p>This uses {@link Expression#generate} with explicit child lists instead of
	 * {@link Expression#replace}, which avoids issues where structural equality matching
	 * could replace unintended sub-expressions in complex expression trees.</p>
	 *
	 * <p>Loop invariance is checked via {@link #isLoopInvariant}, which calls
	 * {@code getDependencies()}, {@code getIndices()}, and {@code containsIndex()} on the
	 * full subtree. This is necessary because some expression types store variable references
	 * internally rather than as child expressions, meaning a bottom-up child-only traversal
	 * (like the previous {@code markVariantNodes} approach) would miss them.</p>
	 *
	 * @param expr the variant expression to process
	 * @param variantNames the set of loop-variant variable names
	 * @param loopIndices all loop indices
	 * @param extracted map of already-extracted sub-expressions to their references (for dedup)
	 * @param extractIdx counter for generating unique declaration names
	 * @param declarations list to receive new extracted declarations
	 * @param minDepth minimum tree depth for extraction (1 for declarations, 3 for non-declarations)
	 * @return the expression with invariant sub-expressions replaced, or the original if unchanged
	 */
	private Expression<?> replaceInvariantSubExpressions(Expression<?> expr,
														 Set<String> variantNames,
														 List<Index> loopIndices,
														 Map<Expression<?>, StaticReference<?>> extracted,
														 int[] extractIdx,
														 List<Statement<?>> declarations,
														 int minDepth) {
		List<Expression<?>> children = expr.getChildren();
		if (children.isEmpty()) return expr;

		List<Expression<?>> newChildren = new ArrayList<>(children);
		boolean changed = false;

		for (int i = 0; i < children.size(); i++) {
			Expression<?> child = children.get(i);

			if (isLoopInvariant(child, variantNames, loopIndices)) {
				if (isSubstantialForExtraction(child, minDepth)) {
					StaticReference<?> ref = extracted.get(child);
					if (ref == null) {
						Class<?> type = child.promoteIfOverflows();
						ref = new StaticReference<>(type,
								"f_licm_" + extractIdx[0]++);
						extracted.put(child, ref);
						declarations.add(new ExpressionAssignment(true, ref, child));
					}
					newChildren.set(i, ref);
					changed = true;
				}
			} else {
				Expression<?> replaced = replaceInvariantSubExpressions(
						child, variantNames, loopIndices, extracted, extractIdx, declarations, minDepth);
				if (replaced != child) {
					newChildren.set(i, replaced);
					changed = true;
				}
			}
		}

		if (!changed) return expr;
		return expr.generate(newChildren);
	}

	/**
	 * Determines whether a loop-invariant sub-expression is substantial enough to
	 * warrant extraction into a separate declaration.
	 *
	 * <p>Constants and simple static references are not worth extracting since they
	 * add a declaration overhead without meaningful reduction in per-iteration
	 * computation.</p>
	 *
	 * <p>The {@code minDepth} parameter controls the extraction threshold and differs
	 * by context:</p>
	 * <ul>
	 *   <li><b>Declaration assignments</b> ({@code minDepth = 1}): These appear in moderate
	 *       numbers so even binary operations like {@code pow(genome, 3.0)} are worth
	 *       extracting.</li>
	 *   <li><b>Non-declaration assignments</b> ({@code minDepth = 3}): Envelope accumulate
	 *       lines and similar non-declaration assignments can contain thousands of trivial
	 *       invariant sub-expressions (array accesses, simple arithmetic). A higher threshold
	 *       filters these out while still capturing genome-derived computations like
	 *       {@code pow((- pow(genome[offset+1], 3.0)) + 1.0, -1.0)}.</li>
	 * </ul>
	 *
	 * @param expr the invariant expression to evaluate
	 * @param minDepth minimum tree depth for extraction
	 * @return true if the expression should be extracted into a declaration
	 */
	private static boolean isSubstantialForExtraction(Expression<?> expr, int minDepth) {
		if (expr instanceof Constant) return false;
		if (expr instanceof StaticReference) return false;
		return expr.treeDepth() >= minDepth
				|| expr.totalComputeCost() >= ScopeSettings.getComputeCostCacheThreshold();
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
	private static <T> Set<String> collectBaseVariantNames(Repeated<T> scope) {
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
	private static void collectBaseVariantNamesRecursive(Scope<?> scope, Set<String> variantNames) {
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
					Scope.collectDestinationNames(assignment.getDestination(), variantNames);
				}
			}
		}

		for (ExpressionAssignment<?> var : scope.getVariables()) {
			Scope.collectDestinationNames(var.getDestination(), variantNames);
		}

		for (Scope<?> child : scope.getChildren()) {
			collectBaseVariantNamesRecursive(child, variantNames);
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
	private static void propagateVariance(List<ExpressionAssignment<?>> declarations,
										  Set<String> variantNames, List<Index> loopIndices) {
		// Precompute declaration names and the set of all names referenced by each
		// declaration's expression in a single O(N) traversal per declaration.
		List<String> declNames = new ArrayList<>(declarations.size());
		List<Set<String>> referencedNameSets = new ArrayList<>(declarations.size());
		List<Boolean> containsLoopIndexFlags = new ArrayList<>(declarations.size());

		for (ExpressionAssignment<?> decl : declarations) {
			declNames.add(Scope.getDeclarationName(decl));

			Expression<?> expr = decl.getExpression();
			Set<String> names = new HashSet<>();
			expr.collectReferencedNames(names);

			boolean hasLoopIdx = false;
			for (Index idx : loopIndices) {
				if (expr.containsIndex(idx)) {
					hasLoopIdx = true;
					break;
				}
			}

			referencedNameSets.add(names);
			containsLoopIndexFlags.add(hasLoopIdx);
		}

		// Fixed-point loop using precomputed name sets
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int i = 0; i < declarations.size(); i++) {
				String name = declNames.get(i);
				if (name != null && !variantNames.contains(name)) {
					if (containsLoopIndexFlags.get(i) || intersectsVariant(referencedNameSets.get(i), variantNames)) {
						variantNames.add(name);
						changed = true;
					}
				}
			}
		}
	}

	/**
	 * Checks whether any name in the referenced set is in the variant set.
	 */
	private static boolean intersectsVariant(Set<String> referencedNames, Set<String> variantNames) {
		for (String name : referencedNames) {
			if (variantNames.contains(name)) return true;
		}
		return false;
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
	private static <T> List<Index> collectLoopIndices(Repeated<T> scope) {
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
	private static void collectLoopIndicesRecursive(Scope<?> scope, List<Index> indices) {
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
		// StaticReference without a referent returns empty from getDependencies(),
		// so we must check by name to avoid incorrectly hoisting dependent expressions.
		return !expr.containsStaticReferenceToAny(loopAssignedVariables);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Creates a new {@link Repeated} scope with the same name, metadata, and index,
	 * then populates it with the provided child scopes.</p>
	 */
	@Override
	public Repeated<T> generate(List<Scope<T>> children) {
		Repeated<T> scope = getMetadata() == null ? new Repeated<>(getName()) : new Repeated<>(getName(), getMetadata());
		scope.setIndex(getIndex());
		scope.getChildren().addAll(children);
		scope.getEpilogue().addAll(getEpilogue());
		return scope;
	}
}
