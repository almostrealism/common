/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.kernel;

import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Tree;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A node in a tree of {@link IndexSequence} values organised by index variable,
 * used to evaluate kernel expressions over multiple non-kernel indices.
 *
 * <p>Each internal node is labelled by the name of an {@link Index} variable and
 * holds one child node per possible value of that index. Leaf nodes hold an
 * {@link IndexSequence} computed by fixing all non-kernel indices to specific values
 * and evaluating the kernel expression at every kernel position.</p>
 *
 * <p>The tree structure allows kernel sequence evaluation to branch efficiently over
 * secondary indices (e.g. batch or channel dimensions) without re-evaluating the
 * entire expression for every combination.</p>
 */
public class KernelSequenceNode implements Tree<KernelSequenceNode> {
	/** The name of the non-kernel index variable that this node branches on, or {@code null} for leaves. */
	private String index;

	/** Child nodes, one per value of the index variable; {@code null} for leaf nodes. */
	private KernelSequenceNode children[];

	/** The sequence stored at this leaf node; {@code null} for internal nodes. */
	private IndexSequence sequence;

	/**
	 * Creates an internal node that branches on the given index name.
	 *
	 * @param index    the name of the index variable
	 * @param children one child per possible value of the index
	 */
	protected KernelSequenceNode(String index, KernelSequenceNode[] children) {
		this.index = index;
		this.children = children;
	}

	/**
	 * Creates a leaf node holding the given pre-computed sequence.
	 *
	 * @param sequence the kernel sequence for this leaf
	 */
	protected KernelSequenceNode(IndexSequence sequence) {
		this.sequence = sequence;
	}

	/** {@inheritDoc} */
	@Override
	public Collection<KernelSequenceNode> getChildren() {
		return children == null ? Collections.emptyList() : List.of(children);
	}

	/**
	 * Builds the full evaluation tree for the given expression at all kernel positions
	 * by branching over every non-kernel index found in the expression.
	 *
	 * @param expression the expression to evaluate
	 * @param len        the number of kernel positions (kernel length)
	 * @return the root of the evaluation tree
	 */
	public static KernelSequenceNode generateTree(Expression<?> expression, int len) {
		Set<Index> indices = expression.getIndices().stream()
				.filter(i -> !(i instanceof KernelIndex)).collect(Collectors.toSet());
		return generateTree(expression, new IndexValues(), indices, len);
	}

	/**
	 * Recursively builds the evaluation tree, fixing each non-kernel index in turn
	 * until all indices are fixed, then evaluating the expression as a leaf sequence.
	 *
	 * @param exp     the expression to evaluate
	 * @param values  the index values fixed so far
	 * @param indices the remaining non-kernel indices to branch over
	 * @param len     the kernel length
	 * @return a node (internal or leaf) for the remaining indices
	 */
	public static KernelSequenceNode generateTree(Expression<?> exp, IndexValues values, Set<Index> indices, int len) {
		if (indices.isEmpty()) {
			return new KernelSequenceNode(ArrayIndexSequence.of(exp, values, len));
		}

		Index index = indices.iterator().next();
		indices = indices.stream().filter(i -> !Objects.equals(i, index)).collect(Collectors.toSet());

		int max = Math.toIntExact(index.getLimit().orElseThrow());

		KernelSequenceNode children[] = new KernelSequenceNode[max];
		for (int i = 0; i < max; i++) {
			IndexValues v = new IndexValues(values).addIndex(index.getName(), i);
			children[i] = generateTree(exp, v, indices, len);
		}

		return new KernelSequenceNode(index.toString(), children);
	}
}
