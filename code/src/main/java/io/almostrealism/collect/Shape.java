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

package io.almostrealism.collect;

public interface Shape<T> extends Traversable<T> {
	TraversalPolicy getShape();

	default T reshape(int... dims) {
		return reshape(new TraversalPolicy(dims));
	}

	T reshape(TraversalPolicy shape);

	default T each() {
		return traverseEach();
	}

	default T all() {
		return traverse(0);
	}

	default T traverseEach() {
		return traverse(getShape().getDimensions());
	}

	default T consolidate() { return traverse(getShape().getTraversalAxis() - 1); }
	default T traverse() { return traverse(getShape().getTraversalAxis() + 1); }
}
