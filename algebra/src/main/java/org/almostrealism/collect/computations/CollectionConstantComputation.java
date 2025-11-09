/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;

/**
 * Abstract base class for computations that produce constant {@link PackedCollection}s.
 *
 * <p>This class extends {@link TraversableExpressionComputation} to provide a foundation
 * for all computations that generate constant values, regardless of inputs. Constant
 * computations have no input dependencies and produce the same values every time they
 * are evaluated.</p>
 *
 * <h2>Constant Computation Properties</h2>
 * <p>All constant computations share these fundamental properties:</p>
 * <ul>
 *   <li><strong>No Input Dependencies:</strong> Output is independent of any inputs</li>
 *   <li><strong>Deterministic:</strong> Always produces the same values</li>
 *   <li><strong>Position-Independent:</strong> Not relative to output position in memory</li>
 *   <li><strong>Compile-Time Evaluable:</strong> Values are known at compilation</li>
 * </ul>
 *
 * <h2>Output-Relative Property</h2>
 * <p>Constant computations override {@link #isOutputRelative()} to return {@code false},
 * indicating that their values are absolute and not dependent on the output buffer's
 * position or addressing. This enables optimizations in code generation and memory
 * allocation.</p>
 *
 * <h2>Subclass Hierarchy</h2>
 * <p>This abstract class is specialized by several concrete implementations:</p>
 * <ul>
 *   <li><strong>{@link SingleConstantComputation}:</strong> All elements have the same value</li>
 *   <li><strong>{@link CollectionZerosComputation}:</strong> All elements are zero (specialized optimization)</li>
 *   <li><strong>{@link EpsilonConstantComputation}:</strong> All elements are machine epsilon</li>
 *   <li><strong>{@link AtomicConstantComputation}:</strong> Single-element constant (strict size constraint)</li>
 * </ul>
 *
 * <h2>Implementation Requirements</h2>
 * <p>Subclasses must implement {@link #getExpression(TraversableExpression...)} to return
 * a {@link ConstantCollectionExpression} that defines the constant values. This expression
 * is used during kernel compilation to generate optimized code.</p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * // Typical subclass implementation:
 * public class MyConstantComputation<T extends PackedCollection<?>>
 *         extends CollectionConstantComputation<T> {
 *
 *     public MyConstantComputation(TraversalPolicy shape) {
 *         super("myConstant", shape);
 *     }
 *
 *     @Override
 *     protected ConstantCollectionExpression getExpression(TraversableExpression... args) {
 *         // Return constant expression with predetermined values
 *         return new ConstantCollectionExpression(getShape(), e(myConstantValue));
 *     }
 * }
 * }</pre>
 *
 * <h2>Optimization Opportunities</h2>
 * <p>Constant computations enable several key optimizations:</p>
 * <ul>
 *   <li>Algebraic simplification (e.g., multiply by zero, add identity)</li>
 *   <li>Compile-time evaluation and folding</li>
 *   <li>Memory layout optimization</li>
 *   <li>Short-circuit evaluation paths</li>
 * </ul>
 *
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see TraversableExpressionComputation
 * @see SingleConstantComputation
 * @see CollectionZerosComputation
 * @see EpsilonConstantComputation
 * @see AtomicConstantComputation
 * @see ConstantCollectionExpression
 *
 * @author Michael Murray
 */
public abstract class CollectionConstantComputation<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {
	/**
	 * Constructs a constant computation with the specified name and output shape.
	 *
	 * @param name The operation identifier for this constant computation
	 * @param shape The {@link TraversalPolicy} defining the output shape
	 */
	public CollectionConstantComputation(String name, TraversalPolicy shape) {
		super(name, shape);
	}

	/**
	 * Indicates that constant computations are not output-relative.
	 *
	 * <p>Constant values are absolute and do not depend on the position of the
	 * output buffer in memory. This property is used by the code generation system
	 * to optimize memory access patterns and enable certain transformations.</p>
	 *
	 * @return Always {@code false} for constant computations
	 */
	@Override
	protected boolean isOutputRelative() { return false; }

	/**
	 * Creates the expression that represents this constant computation.
	 *
	 * <p>Subclasses must implement this method to return a {@link ConstantCollectionExpression}
	 * that defines the constant values to be produced. The expression will be used during
	 * kernel compilation to generate optimized code for producing the constant values.</p>
	 *
	 * @param args Array of {@link TraversableExpression}s (typically empty or unused for constants)
	 * @return A {@link ConstantCollectionExpression} defining the constant values
	 */
	@Override
	protected abstract ConstantCollectionExpression getExpression(TraversableExpression... args);
}
