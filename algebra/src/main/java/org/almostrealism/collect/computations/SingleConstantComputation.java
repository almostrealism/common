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
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.util.NumberFormats;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalDouble;

/**
 * A {@link SingleConstantComputation} represents a computation that produces a 
 * {@link PackedCollection} where every element has the same constant value.
 * This is an optimized implementation for cases where the entire collection
 * should be filled with a single numeric value.
 * 
 * <p>This class extends {@link CollectionConstantComputation} and provides
 * efficient handling of constant values throughout the computation pipeline.
 * It includes optimizations for common operations like addition and multiplication
 * when dealing with constant values.</p>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>{@code
 * // Create a 3x3 matrix filled with the value 5.0
 * TraversalPolicy shape = new TraversalPolicy(3, 3);
 * SingleConstantComputation<PackedCollection> constant = 
 *     new SingleConstantComputation<>(shape, 5.0);
 * 
 * // Evaluate to get the actual collection
 * PackedCollection result = constant.get().evaluate();
 * // result will be a 3x3 collection where every element is 5.0
 * 
 * // Create a constant vector
 * TraversalPolicy vectorShape = new TraversalPolicy(5);
 * SingleConstantComputation<PackedCollection> vector = 
 *     new SingleConstantComputation<>(vectorShape, 1.0);
 * }</pre>
 * 
 * <h3>Optimizations:</h3>
 * <p>This class provides several optimizations:</p>
 * <ul>
 *   <li>Zero detection - {@link #isZero()} returns true when value is 0.0</li>
 *   <li>Identity detection - {@link #isIdentity(int)} returns true for scalar 1.0</li>
 *   <li>Short-circuit evaluation - {@link #getShortCircuit()} provides direct computation</li>
 *   <li>Arithmetic optimization - Used in {@link org.almostrealism.collect.CollectionFeatures}
 *       for optimizing operations with constant operands</li>
 * </ul>
 *
 * @author Michael Murray
 * @see CollectionConstantComputation
 * @see PackedCollection
 * @see TraversalPolicy
 * @see org.almostrealism.collect.CollectionFeatures#constant(TraversalPolicy, double)
 */
public class SingleConstantComputation extends CollectionConstantComputation {
	/**
	 * The constant value that will fill every element of the produced collection.
	 * This value is immutable once set during construction.
	 */
	protected final double value;

	/**
	 * Creates a new SingleConstantComputation with the specified shape and constant value.
	 * The computation will be named using the format "constant(value)" where value is
	 * formatted using {@link NumberFormats#formatNumber(Number)}.
	 * 
	 * @param shape The traversal policy defining the dimensions and structure of the output collection
	 * @param value The constant value to fill every element of the collection
	 * 
	 * @throws IllegalArgumentException if shape is null
	 * 
	 * @see #SingleConstantComputation(String, TraversalPolicy, double)
	 * @see TraversalPolicy
	 */
	public SingleConstantComputation(TraversalPolicy shape, double value) {
		this("constant(" + NumberFormats.formatNumber(value) + ")", shape, value);
	}

	/**
	 * Creates a new SingleConstantComputation with a custom name, shape, and constant value.
	 * This constructor is typically used internally or by subclasses that need to specify
	 * a custom name for the computation.
	 * 
	 * @param name The name identifier for this computation
	 * @param shape The traversal policy defining the dimensions and structure of the output collection
	 * @param value The constant value to fill every element of the collection
	 * 
	 * @throws IllegalArgumentException if shape is null
	 * 
	 * @see #SingleConstantComputation(TraversalPolicy, double)
	 */
	protected SingleConstantComputation(String name, TraversalPolicy shape, double value) {
		super(name, shape);
		this.value = value;
	}

	@Override
	protected ConstantCollectionExpression getExpression(TraversableExpression... args) {
		return new ConstantCollectionExpression(getShape(), e(value));
	}

	@Override
	public OptionalDouble getConstant() { return OptionalDouble.of(value); }

	/**
	 * Returns the constant value that this computation produces for every element.
	 * 
	 * @return The constant double value
	 */
	public double getConstantValue() { return value; }

	/**
	 * Provides a short-circuit evaluation that directly creates and fills a PackedCollection
	 * with the constant value, bypassing the normal computation pipeline for efficiency.
	 * This is particularly useful for constant values as it avoids unnecessary kernel compilation.
	 * 
	 * @return An Evaluable that directly produces the constant-filled collection
	 */
	@Override
	public Evaluable<PackedCollection> getShortCircuit() {
		return args -> {
			PackedCollection v = new PackedCollection(getShape());
			v.fill(value);
			return getPostprocessor() == null ? v : getPostprocessor().apply(v, 0);
		};
	}

	/**
	 * Determines if this constant computation represents a zero value.
	 * This is used for algebraic optimizations where operations with zero
	 * can be simplified.
	 * 
	 * @return true if the constant value is exactly 0.0, false otherwise
	 */
	@Override
	public boolean isZero() { return value == 0.0; }

	/**
	 * Determines if this constant computation represents an identity element
	 * for multiplication operations. An identity element is 1.0 for a scalar
	 * (single element collection).
	 * 
	 * @param width The expected width for identity check (must be 1 for scalar identity)
	 * @return true if value is 1.0, width is 1, and the collection has exactly one element
	 */
	@Override
	public boolean isIdentity(int width) {
		return value == 1.0 && width == 1 && getShape().getTotalSizeLong() == 1;
	}

	/**
	 * Generates a parallel process for this computation. Since this is a constant computation
	 * with no dependencies, it returns itself as the process.
	 * 
	 * @param children The list of child processes (unused for constant computations)
	 * @return This computation instance as it serves as its own process
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return this;
	}

	/**
	 * Isolation is not supported for single constant computations.
	 *
	 * <p>In practice, constant computations are so trivial that isolation
	 * would provide no benefit and might actually reduce performance
	 * by adding unnecessary computation steps while wasting memory.</p>
	 *
	 * @return This instance itself, as it cannot be isolated
	 *
	 * @see Process#isolate()
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends PackedCollection>> isolate() {
		return this;
	}

	/**
	 * Creates a new SingleConstantComputation with the shape traversed along the specified axis.
	 * The constant value is preserved while the shape is transformed according to the
	 * traversal policy's traverse operation.
	 * 
	 * @param axis The axis along which to perform the traversal
	 * @return A new SingleConstantComputation with the traversed shape and same constant value
	 * 
	 * @see TraversalPolicy#traverse(int)
	 */
	@Override
	public CollectionProducer traverse(int axis) {
		return new SingleConstantComputation(getShape().traverse(axis), value);
	}

	/**
	 * Creates a new SingleConstantComputation with the specified shape while preserving
	 * the constant value. This allows reshaping the output collection dimensions without
	 * changing the constant value that fills it.
	 * 
	 * @param shape The new traversal policy defining the desired output shape
	 * @return A new SingleConstantComputation with the new shape and same constant value
	 * 
	 * @throws IllegalArgumentException if the new shape is not compatible with the total size
	 */
	@Override
	public CollectionProducerComputation reshape(TraversalPolicy shape) {
		return new SingleConstantComputation(shape, value);
	}

	/**
	 * Returns a string description of this computation, which is the formatted constant value.
	 * 
	 * @return A formatted string representation of the constant value
	 */
	@Override
	public String description() { return NumberFormats.formatNumber(value); }
}
