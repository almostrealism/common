/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.graph;

import io.almostrealism.relation.Graph;

/**
 * A computation graph composed of interconnected {@link Cell} nodes.
 *
 * <p>{@code Automata} extends the {@link io.almostrealism.relation.Graph} interface
 * to represent a directed graph where each node is a {@link Cell}. This provides
 * the structural definition for cellular automaton-style computation networks.</p>
 *
 * @param <T> the type of data processed by each cell node
 * @see Cell
 * @see io.almostrealism.relation.Graph
 * @author Michael Murray
 */
public interface Automata<T> extends Graph<Cell<T>> {
	/**
	 * {@inheritDoc}
	 *
	 * @throws UnsupportedOperationException always, as node counting is not supported for Automata
	 */
	@Override
	default int countNodes() {
		throw new UnsupportedOperationException();
	}
}
