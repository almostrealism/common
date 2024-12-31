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

package io.almostrealism.relation;

import java.util.Collection;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

public interface Tree<T extends Tree> extends Graph<T>, NodeGroup<T>, Parent<T>, Node {

	// TODO   Rename "all", with children() instead defaulting to includeThis = false
	default Stream<T> children() {
		return children(true);
	}

	default Stream<T> children(boolean includeThis) {
		Stream<T> c = getChildren().stream().flatMap(Tree::children);
		return includeThis ? Stream.concat(Stream.of((T) this), c) : c;
	}

	default TreeRef<T> ref() {
		return new TreeRef<>(this);
	}
	
	@Override
	default Collection<T> neighbors(T node) {
		throw new UnsupportedOperationException();
	}

	@Override
	default int countNodes() {
		return 1 + getChildren().stream().mapToInt(Tree::countNodes).sum();
	}

	default int treeDepth() {
		return countDepth(t -> 1, t -> true);
	}

	default int treeDepth(Predicate<T> filter) {
		return countDepth(t -> 1, filter);
	}

	default int countDepth(ToIntFunction<Parent<T>> count, Predicate<T> filter) {
		return count.applyAsInt(this) + getChildren().stream().filter(filter)
				.mapToInt(t -> t.countDepth(count, filter))
				.max().orElse(0);
	}
}
