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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

/**
 * A computation that produces a single-element constant value (atomic/scalar constant).
 *
 * <p>This class extends {@link SingleConstantComputation} to enforce a strict constraint
 * that the shape must contain exactly one element. It represents truly atomic constant values
 * that cannot be broadcasted or reshaped into multi-element collections.</p>
 *
 * <h2>Atomic Constraint</h2>
 * <p>The fundamental property of this computation is that it <strong>must</strong> have a total
 * size of exactly 1 element. Attempts to construct or reshape with any other size will throw
 * {@link IllegalArgumentException}.</p>
 *
 * <h2>Default Value</h2>
 * <p>When constructed without arguments, this computation defaults to the value 1.0,
 * making it convenient for identity operations in multiplication or similar contexts.</p>
 *
 * <h2>Comparison with Related Classes</h2>
 * <ul>
 *   <li><strong>AtomicConstantComputation:</strong> Single element only (this class)</li>
 *   <li><strong>{@link SingleConstantComputation}:</strong> Single value, any shape (broadcasts)</li>
 *   <li><strong>{@link CollectionConstantComputation}:</strong> Different value per element</li>
 *   <li><strong>{@link EpsilonConstantComputation}:</strong> Machine epsilon value</li>
 *   <li><strong>{@link CollectionZerosComputation}:</strong> All zeros</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Default unit constant (1.0):</strong></p>
 * <pre>{@code
 * AtomicConstantComputation<PackedCollection> one = new AtomicConstantComputation<>();
 * PackedCollection result = one.get().evaluate();
 * // Result: [1.0]  (single element)
 * }</pre>
 *
 * <p><strong>Custom scalar constant:</strong></p>
 * <pre>{@code
 * AtomicConstantComputation<PackedCollection> pi = new AtomicConstantComputation<>(3.14159);
 * PackedCollection result = pi.get().evaluate();
 * // Result: [3.14159]  (single element)
 * }</pre>
 *
 * <p><strong>Using as a scaling factor:</strong></p>
 * <pre>{@code
 * CollectionProducer<PackedCollection> data = c(1.0, 2.0, 3.0);
 * CollectionProducer<PackedCollection> scale = new AtomicConstantComputation<>(2.0);
 * CollectionProducer<PackedCollection> scaled = data.multiply(scale);
 * // Result: [2.0, 4.0, 6.0]  (scale is broadcast)
 * }</pre>
 *
 * <p><strong>Invalid usage (throws exception):</strong></p>
 * <pre>{@code
 * // This will throw IllegalArgumentException at construction time:
 * AtomicConstantComputation<PackedCollection> invalid =
 *     new AtomicConstantComputation<>(shape(3), 1.0);  // ERROR: size != 1
 * }</pre>
 *
 * <h2>Reshape and Traverse Behavior</h2>
 * <p>The {@link #reshape(TraversalPolicy)} method maintains the atomic constraint:</p>
 * <ul>
 *   <li>Reshaping to size 1: Returns new {@code AtomicConstantComputation}</li>
 *   <li>Reshaping to size != 1: Delegates to parent (broadcast behavior)</li>
 * </ul>
 * <p>The {@link #traverse(int)} method always returns an atomic computation since
 * traversal of a single element still results in a single element.</p>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Memory:</strong> Minimal (single value, no allocation)</li>
 *   <li><strong>Evaluation:</strong> O(1) constant time</li>
 *   <li><strong>Broadcasting:</strong> Efficiently handled in element-wise operations</li>
 * </ul>
 *
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see SingleConstantComputation
 * @see CollectionConstantComputation
 * @see org.almostrealism.collect.CollectionFeatures#c(double)
 *
 * @author Michael Murray
 */
public class AtomicConstantComputation<T extends PackedCollection> extends SingleConstantComputation<T> {
	/**
	 * Constructs an atomic constant with the default value of 1.0.
	 * The resulting collection will have exactly one element with value 1.0.
	 */
	public AtomicConstantComputation() { this(1.0); }

	/**
	 * Constructs an atomic constant with the specified value.
	 * The resulting collection will have exactly one element with the given value.
	 *
	 * @param value The constant value to produce
	 */
	public AtomicConstantComputation(double value) {
		this(new TraversalPolicy(1), value);
	}

	/**
	 * Protected constructor with explicit shape specification.
	 * Validates that the shape has exactly one element.
	 *
	 * @param shape The {@link TraversalPolicy} defining the shape (must have total size 1)
	 * @param value The constant value to produce
	 * @throws IllegalArgumentException if {@code shape.getTotalSizeLong() != 1}
	 */
	protected AtomicConstantComputation(TraversalPolicy shape, double value) {
		super(shape, value);

		if (shape.getTotalSizeLong() != 1) {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Creates a traversal view of this atomic constant.
	 * Since an atomic constant has only one element, traversal always returns
	 * another atomic constant with the same value.
	 *
	 * @param axis The axis to traverse (ignored for atomic constants)
	 * @return A new {@link AtomicConstantComputation} with traversed shape
	 */
	@Override
	public CollectionProducer<T> traverse(int axis) {
		return new AtomicConstantComputation<>(getShape().traverse(1), value);
	}

	/**
	 * Reshapes this atomic constant to the specified shape.
	 *
	 * <p>The behavior depends on the target shape size:</p>
	 * <ul>
	 *   <li><strong>Size = 1:</strong> Returns new {@code AtomicConstantComputation} with new shape</li>
	 *   <li><strong>Size != 1:</strong> Delegates to parent {@link SingleConstantComputation#reshape(TraversalPolicy)}
	 *       which broadcasts the constant value to all elements</li>
	 * </ul>
	 *
	 * @param shape The target {@link TraversalPolicy} for reshaping
	 * @return An {@link AtomicConstantComputation} if size is 1, otherwise a broadcasted constant
	 */
	@Override
	public CollectionProducerComputation<T> reshape(TraversalPolicy shape) {
		if (shape.getTotalSizeLong() == 1) {
			return new AtomicConstantComputation<>(shape, value);
		}

		return super.reshape(shape);
	}

	/**
	 * Returns a string description of this computation.
	 * Delegates to the inherited {@link #description()} method.
	 *
	 * @return A string describing this atomic constant computation
	 */
	@Override
	public String describe() { return description(); }

}
