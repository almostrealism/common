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

package org.almostrealism;

import java.util.function.Function;

/**
 * The singleton entry point for accessing all operation features in the Almost Realism framework.
 * Ops provides convenient access to all computational operations through the {@link CodeFeatures}
 * interface without requiring class instantiation.
 *
 * <p>This class follows the singleton pattern with a fluent API style. The primary access
 * method is {@link #o()}, which returns the singleton instance that can be used to invoke
 * any operation from the extensive feature interfaces.</p>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Direct Access</h3>
 * <pre>{@code
 * import static org.almostrealism.Ops.o;
 *
 * // Create producers
 * CollectionProducer<?> a = o().c(1.0, 2.0, 3.0);
 * CollectionProducer<?> b = o().c(4.0, 5.0, 6.0);
 *
 * // Perform operations
 * CollectionProducer<?> sum = o().add(a, b);
 * CollectionProducer<?> product = o().multiply(a, b);
 * }</pre>
 *
 * <h3>Functional Style</h3>
 * <pre>{@code
 * // Use op() for functional composition
 * PackedCollection result = Ops.op(o -> o.multiply(o.c(2.0), o.p(input))).evaluate();
 * }</pre>
 *
 * <h3>In Classes Implementing CodeFeatures</h3>
 * <p>When implementing CodeFeatures, operations are available directly:</p>
 * <pre>{@code
 * public class MyProcessor implements CodeFeatures {
 *     public void process() {
 *         // Operations available directly
 *         CollectionProducer<?> result = multiply(c(2.0), p(input));
 *     }
 * }
 * }</pre>
 *
 * <h2>Available Operations</h2>
 * <p>Through CodeFeatures, Ops provides access to:</p>
 * <ul>
 *   <li>Collection operations (add, multiply, reshape, etc.)</li>
 *   <li>Matrix operations (matmul, transpose, etc.)</li>
 *   <li>Neural network layers (dense, conv2d, norm, etc.)</li>
 *   <li>Activation functions (relu, silu, gelu, softmax, etc.)</li>
 *   <li>Producer creation (c(), p(), v(), etc.)</li>
 *   <li>Hardware context management (dc(), cc())</li>
 * </ul>
 *
 * @see CodeFeatures
 * @see org.almostrealism.layers.LayerFeatures
 * @author Michael Murray
 */
public class Ops implements CodeFeatures {
	private static final Ops ops = new Ops();

	private Ops() { }

	/**
	 * Returns the singleton Ops instance.
	 * This is the primary entry point for accessing all framework operations.
	 *
	 * <p>Common usage:</p>
	 * <pre>{@code
	 * import static org.almostrealism.Ops.o;
	 * CollectionProducer<?> result = o().multiply(a, b);
	 * }</pre>
	 *
	 * @return the singleton Ops instance
	 */
	public static Ops o() { return ops; }

	/**
	 * Applies a function using the Ops instance and returns the result.
	 * This enables functional-style operations without storing intermediate references.
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * CollectionProducer<?> result = Ops.op(o -> o.add(o.c(1), o.c(2)));
	 * }</pre>
	 *
	 * @param <T> the return type of the function
	 * @param op a function that takes an Ops instance and returns a result
	 * @return the result of applying the function
	 */
	public static <T> T op(Function<Ops, T> op) {
		return op.apply(o());
	}
}
