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

package io.almostrealism.relation;

import java.util.Collection;
import java.util.stream.Stream;

public interface Tree<T extends Tree> extends Graph<T>, NodeGroup<T>, Parent<T>, Node {

	default Stream<T> children() {
		return Stream.concat(Stream.of((T) this), getChildren().stream().flatMap(Tree::children));
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
		throw new UnsupportedOperationException();
	}
}
