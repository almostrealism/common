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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Index;
import io.almostrealism.expression.IndexValues;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.relation.Tree;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class KernelSequenceNode implements Tree<KernelSequenceNode> {
	private String index;
	private KernelSequenceNode children[];

	private IndexSequence sequence;

	protected KernelSequenceNode(String index, KernelSequenceNode[] children) {
		this.index = index;
		this.children = children;
	}

	protected KernelSequenceNode(IndexSequence sequence) {
		this.sequence = sequence;
	}

	@Override
	public Collection<KernelSequenceNode> getChildren() {
		return children == null ? Collections.emptyList() : List.of(children);
	}

	public static KernelSequenceNode generateTree(Expression<?> expression, int len) {
		Set<Index> indices = expression.getIndices().stream()
				.filter(i -> !(i instanceof KernelIndex)).collect(Collectors.toSet());
		return generateTree(expression, new IndexValues(), indices, len);
	}

	public static KernelSequenceNode generateTree(Expression<?> exp, IndexValues values, Set<Index> indices, int len) {
		if (indices.isEmpty()) {
			return new KernelSequenceNode(IndexSequence.of(exp, values, len));
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
