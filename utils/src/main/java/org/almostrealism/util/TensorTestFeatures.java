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

package org.almostrealism.util;

import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.TraversalPolicy;

import java.util.function.Predicate;
import java.util.stream.IntStream;

public interface TensorTestFeatures {
	default Tensor<Double> tensor(TraversalPolicy shape) {
		return tensor(shape, (pos) -> true);
	}

	default Tensor<Double> tensor(TraversalPolicy shape, Predicate<int[]> condition) {
		Tensor<Double> t = new Tensor<>();

		shape.stream().forEach(pos -> {
			boolean inside = condition.test(pos);
			double multiplier = inside ? 1 : -1;
			t.insert(multiplier * (IntStream.of(pos).sum()), pos);
		});

		return t;
	}
}
