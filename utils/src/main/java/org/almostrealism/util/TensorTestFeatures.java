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
import io.almostrealism.collect.TraversalPolicy;

import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Testing interface providing utilities for creating test tensors with predictable values.
 *
 * <p>This interface provides methods to create {@link Tensor} instances filled with
 * deterministic values based on position indices. The generated values are useful for
 * testing tensor operations where you need predictable, position-dependent data.</p>
 *
 * <h2>Value Generation</h2>
 * <p>Tensor values are computed as the sum of position indices, optionally negated
 * based on a condition predicate. This creates a gradient of values that makes it
 * easy to verify that tensor operations preserve or correctly transform spatial
 * relationships.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class MyTest implements TensorTestFeatures, CodeFeatures {
 *     @Test
 *     public void testTensorOperation() {
 *         // Create a 3x4 tensor with position-based values
 *         Tensor<Double> t = tensor(shape(3, 4));
 *         // Values: [0][0]=0, [0][1]=1, [1][0]=1, [1][1]=2, etc.
 *
 *         // Create tensor with conditional values (negative outside region)
 *         Tensor<Double> t2 = tensor(shape(10, 10),
 *             pos -> pos[0] >= 2 && pos[0] < 8);
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see TestFeatures for the primary testing interface
 */
public interface TensorTestFeatures {

	/**
	 * Creates a tensor with position-based values for the given shape.
	 * Each element's value equals the sum of its position indices.
	 *
	 * @param shape the shape of the tensor to create
	 * @return a new tensor with values based on position sums
	 */
	default Tensor<Double> tensor(TraversalPolicy shape) {
		return tensor(shape, (pos) -> true);
	}

	/**
	 * Creates a tensor with conditional position-based values.
	 * Elements where the condition is true have positive values (sum of indices);
	 * elements where the condition is false have negative values.
	 *
	 * @param shape     the shape of the tensor to create
	 * @param condition a predicate tested against each position; determines sign of value
	 * @return a new tensor with signed values based on position sums and condition
	 */
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
