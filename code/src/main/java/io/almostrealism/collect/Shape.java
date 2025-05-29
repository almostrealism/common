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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;
import org.almostrealism.io.Describable;

import java.util.stream.IntStream;

public interface Shape<T> extends Traversable<T>, IndexSet, Describable {
	TraversalPolicy getShape();
	
	default T reshape(int... dims) {
		if (IntStream.range(0, dims.length).filter(i -> dims[i] == 0).findAny().isPresent())
			throw new IllegalArgumentException("Cannot reshape with a dimension of length 0");

		int inf[] = IntStream.range(0, dims.length).filter(i -> dims[i] < 0).toArray();
		if (inf.length > 1) throw new IllegalArgumentException("Only one dimension can be inferred");
		if (inf.length == 1) {
			TraversalPolicy shape = getShape();
			long tot = shape.getTotalSizeLong();
			int known = IntStream.of(dims).filter(i -> i >= 0).reduce(1, (a, b) -> a * b);
			return reshape(new TraversalPolicy(IntStream.range(0, dims.length)
					.mapToLong(i -> i == inf[0] ? tot / known : dims[i]).toArray()));
		}

		return reshape(new TraversalPolicy(dims));
	}

	T reshape(TraversalPolicy shape);

	default T flatten() {
		return reshape(getShape().getTotalSize());
	}

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

	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		return getShape().getOrder().containsIndex(index);
	}

	@Override
	default String describe() {
		return getShape().toStringDetail();
	}
}
