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

package io.almostrealism.expression;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.Variable;
import io.almostrealism.sequence.Index;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Queries over what an {@link Expression} tree refers to: the subexpressions, indices,
 * variables, and names that occur anywhere beneath a node.
 *
 * <p>The queries are built on three primitives every implementation supplies — its
 * {@link #getChildren() children}, its {@link #getDependencies() variable dependencies}, and
 * its {@link #getIndices() indices} — and answer by walking the subtree through them. A node
 * that is itself something a query looks for answers that part for itself by overriding the
 * query, and consults its children through {@code super}: a {@link Constant} references no
 * index; the indices ({@link io.almostrealism.sequence.DefaultIndex},
 * {@link io.almostrealism.sequence.IndexChild}) match their own name; {@link StaticReference}
 * and {@link InstanceReference} match their own referent; and {@link StaticReference} and
 * {@link io.almostrealism.sequence.IndexChild} contribute their own name to the referenced
 * names. {@link Expression} implements this interface, so every expression exposes these
 * queries.</p>
 *
 * <p>Each query visits the children of every node it reaches, and compilation evaluates them
 * over trees of millions of nodes with only a few children each, so the children are walked
 * with plain loops that stop at the first decisive child.</p>
 */
public interface ExpressionReferences {

	/**
	 * Returns the operands of this expression, the subtrees each query descends into.
	 *
	 * @return the child expressions; empty for a leaf
	 */
	List<Expression<?>> getChildren();

	/**
	 * Returns the variables this expression tree depends on.
	 *
	 * @return the variables referenced by this expression tree
	 */
	List<Variable<?, ?>> getDependencies();

	/**
	 * Returns the indices referenced anywhere in this expression tree.
	 *
	 * @return the indices referenced by this expression tree
	 */
	Set<Index> getIndices();

	/**
	 * Checks if this expression contains the specified expression anywhere in its subtree.
	 *
	 * <p>Uses structural equality ({@link Expression#equals(Object)}) for comparison.</p>
	 *
	 * @param e the expression to search for
	 * @return {@code true} if the expression is found in this subtree
	 */
	default boolean contains(Expression e) {
		if (this.equals(e)) return true;

		for (Expression<?> child : getChildren()) {
			if (child.contains(e)) return true;
		}

		return false;
	}

	/**
	 * Checks if this expression references the specified index anywhere in its subtree.
	 *
	 * <p>Indices are compared by name using {@link Index#getName()}. This base
	 * implementation delegates to the children; the index expressions
	 * ({@link io.almostrealism.sequence.DefaultIndex}, {@link io.almostrealism.sequence.IndexChild})
	 * override it to match their own name.</p>
	 *
	 * @param idx the index to search for
	 * @return {@code true} if the index is referenced in this subtree
	 */
	default boolean containsIndex(Index idx) {
		for (Expression<?> child : getChildren()) {
			if (child.containsIndex(idx)) return true;
		}

		return false;
	}

	/**
	 * Checks if this expression references the specified variable anywhere in its subtree.
	 *
	 * @param var the variable to search for
	 * @return {@code true} if the variable is referenced in this subtree
	 */
	default boolean containsReference(Variable var) {
		for (Expression<?> child : getChildren()) {
			if (child.containsReference(var)) return true;
		}

		return false;
	}

	/**
	 * Checks whether this expression tree references any variable named in {@code names}.
	 *
	 * <p>This is the name-based counterpart to {@link #containsReference(Variable)}, for
	 * callers that hold variable names rather than {@link Variable} instances. Like that
	 * method, this base implementation delegates to the children; the reference subclasses
	 * ({@link StaticReference}, {@link InstanceReference}) override it to match their own
	 * referent name.</p>
	 *
	 * @param names the variable names to look for
	 * @return true if any matching reference is found anywhere in this subtree
	 */
	default boolean containsReference(Set<String> names) {
		for (Expression<?> child : getChildren()) {
			if (child.containsReference(names)) return true;
		}

		return false;
	}

	/**
	 * Returns the kernel structure context associated with this expression.
	 *
	 * <p>The context provides information about the kernel structure, including
	 * maximum kernel size and series providers for optimization. This base implementation
	 * searches the children, returning the first context found; the
	 * {@link io.almostrealism.kernel.KernelIndex} overrides it to report its own.</p>
	 *
	 * @return the kernel structure context, or {@code null} if none is associated
	 */
	default KernelStructureContext getStructureContext() {
		for (Expression<?> child : getChildren()) {
			KernelStructureContext context = child.getStructureContext();
			if (context != null) return context;
		}

		return null;
	}

	/**
	 * Determines whether this expression yields the same value on every iteration of a
	 * loop. The expression is loop-variant if it references one of the loop indices, a
	 * loop-assigned scalar (by name), or an element of an array the loop writes (whose
	 * value changes as the loop runs).
	 *
	 * @param loopVariantNames names of the loop index and scalars assigned in the loop
	 * @param writtenArrays    the arrays written inside the loop
	 * @param loopIndices      indices from the loop and any nested loops
	 * @return true if this expression's value cannot change across iterations
	 */
	default boolean isLoopInvariant(Set<String> loopVariantNames,
									Collection<Variable<?, ?>> writtenArrays, List<Index> loopIndices) {
		for (Index idx : loopIndices) {
			if (containsIndex(idx)) return false;
		}

		for (Variable<?, ?> var : getDependencies()) {
			if (var.getName() != null && loopVariantNames.contains(var.getName())) return false;
		}

		for (Index idx : getIndices()) {
			if (idx.getName() != null && loopVariantNames.contains(idx.getName())) return false;
		}

		if (containsReference(loopVariantNames)) return false;

		for (Variable<?, ?> array : writtenArrays) {
			if (containsReference(array)) return false;
		}

		return true;
	}

	/**
	 * Collects all variable, index, and {@link StaticReference} names referenced
	 * by this expression tree in a single traversal.
	 *
	 * <p>This base implementation adds, at each leaf, the names from
	 * {@link #getDependencies()} and {@link #getIndices()}, and recurses into the
	 * children. The named nodes ({@link StaticReference}, including the
	 * {@link io.almostrealism.sequence.DefaultIndex} indices, and
	 * {@link io.almostrealism.sequence.IndexChild}) override it to add their own name as
	 * well.</p>
	 *
	 * <p>The combined set represents every named entity this expression depends on,
	 * which is useful for variance analysis, dependency tracking, and scope optimization.</p>
	 *
	 * @param names the output set to which all referenced names are added
	 */
	default void collectReferencedNames(Set<String> names) {
		List<Expression<?>> children = getChildren();
		if (children.isEmpty()) {
			for (Variable<?, ?> var : getDependencies()) {
				if (var.getName() != null) names.add(var.getName());
			}
			for (Index idx : getIndices()) {
				if (idx.getName() != null) names.add(idx.getName());
			}
		}

		for (Expression<?> child : children) {
			child.collectReferencedNames(names);
		}
	}
}
