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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A computation that extracts a subset (slice) from a {@link PackedCollection} based on specified
 * shape and position parameters. This class enables efficient extraction of multi-dimensional
 * sub-arrays from larger collections, which is fundamental for tensor operations, convolutions,
 * and data windowing in machine learning and scientific computing.
 *
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li>Supports 1D, 2D, 3D, and higher-dimensional collections</li>
 *   <li>Allows both static positions (compile-time constants) and dynamic positions (runtime expressions)</li>
 *   <li>Preserves data types and maintains efficient memory access patterns</li>
 *   <li>Integrates with the broader AlmostRealism computation graph system</li>
 * </ul>
 *
 * <p><strong>Usage Examples:</strong></p>
 *
 * <p><em>1. Basic 2D subset extraction:</em></p>
 * <pre>{@code
 * // Create a 10x10 collection
 * PackedCollection<?> input = new PackedCollection<>(shape(10, 10));
 * input.fill(Math::random);
 * 
 * // Extract a 3x3 subset starting at position (2, 3)
 * CollectionProducer<PackedCollection<?>> producer = subset(shape(3, 3), p(input), 2, 3);
 * PackedCollection<?> result = producer.get().evaluate();
 * 
 * // Result is a 3x3 collection containing values from input[2:5, 3:6]
 * }</pre>
 *
 * <p><em>2. 3D subset with static positions:</em></p>
 * <pre>{@code
 * PackedCollection<?> volume = new PackedCollection<>(shape(100, 100, 50));
 * volume.fill(Math::random);
 * 
 * // Extract a 10x10x5 cube starting at (20, 30, 10)
 * CollectionProducer<PackedCollection<?>> cubeProducer = 
 *     subset(shape(10, 10, 5), p(volume), 20, 30, 10);
 * PackedCollection<?> cube = cubeProducer.get().evaluate();
 * }</pre>
 *
 * <p><em>3. Dynamic subset with computed positions:</em></p>
 * <pre>{@code
 * PackedCollection<?> data = new PackedCollection<>(shape(50, 50));
 * data.fill(Math::random);
 * 
 * // Position determined at runtime
 * PackedCollection<?> position = new PackedCollection<>(2);
 * position.set(0, 15.0); // x-offset
 * position.set(1, 25.0); // y-offset
 * 
 * // Extract 5x5 subset at dynamic position
 * CollectionProducer<PackedCollection<?>> dynamicProducer = 
 *     subset(shape(5, 5), p(data), p(position));
 * PackedCollection<?> window = dynamicProducer.get().evaluate();
 * }</pre>
 *
 * <p><em>4. Integration with other operations:</em></p>
 * <pre>{@code
 * // Subset can be combined with other operations like convolution
 * PackedCollection<?> image = new PackedCollection<>(shape(256, 256));
 * PackedCollection<?> kernel = new PackedCollection<>(shape(3, 3));
 * 
 * // Extract patches for convolution
 * for (int y = 0; y < 254; y++) {
 *     for (int x = 0; x < 254; x++) {
 *         CollectionProducer<PackedCollection<?>> patch = 
 *             subset(shape(3, 3), p(image), x, y);
 *         // Apply kernel to patch...
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li>The subset operation copies data from the source to a new collection using optimized index projection</li>
 *   <li>Memory access patterns are optimized for the underlying hardware</li>
 *   <li>Static positions enable compile-time optimizations</li>
 *   <li>Dynamic positions provide runtime flexibility at a small performance cost</li>
 * </ul>
 *
 * @param <T> The type of PackedCollection being subset, maintaining type safety
 *
 * @see CollectionFeatures#subset(TraversalPolicy, Producer, int...)
 * @see CollectionFeatures#subset(TraversalPolicy, Producer, Expression...)
 * @see CollectionFeatures#subset(TraversalPolicy, Producer, Producer)
 * @see PackedCollection
 * @see TraversalPolicy
 *
 * @author Michael Murray
 * @since 0.68
 */
public class PackedCollectionSubset<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	private Expression pos[];

	/**
	 * Creates a subset computation with static integer positions.
	 * This is the most common constructor for extracting subsets at compile-time known positions.
	 *
	 * <p>Example usage:</p>
	 * <pre>{@code
	 * // Extract a 5x5 subset starting at position (10, 15) from a 2D collection
	 * PackedCollectionSubset<PackedCollection<?>> subset = 
	 *     new PackedCollectionSubset<>(shape(5, 5), collection, 10, 15);
	 * }</pre>
	 *
	 * @param shape The desired shape (dimensions) of the resulting subset
	 * @param collection The source collection to extract from, must implement {@link Shape}
	 * @param pos The starting position coordinates as integers, one per dimension
	 * @throws IllegalArgumentException if collection doesn't implement Shape interface
	 * @throws IllegalArgumentException if pos array length doesn't match input collection dimensions
	 */
	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, int... pos) {
		this(shape, collection, IntStream.of(pos).mapToObj(i -> new IntegerConstant(i)).toArray(Expression[]::new));
	}

	/**
	 * Creates a subset computation with expression-based positions.
	 * This constructor allows for more complex position calculations that may involve
	 * runtime expressions or computed values.
	 *
	 * <p>Example usage:</p>
	 * <pre>{@code
	 * // Extract subset with computed starting positions
	 * Expression xOffset = e(baseX).add(e(deltaX));
	 * Expression yOffset = e(baseY).add(e(deltaY));
	 * PackedCollectionSubset<PackedCollection<?>> subset = 
	 *     new PackedCollectionSubset<>(shape(3, 3), collection, xOffset, yOffset);
	 * }</pre>
	 *
	 * @param shape The desired shape (dimensions) of the resulting subset
	 * @param collection The source collection to extract from, must implement {@link Shape}
	 * @param pos The starting position coordinates as expressions, one per dimension
	 * @throws IllegalArgumentException if collection doesn't implement Shape interface
	 */
	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, Expression... pos) {
		super("subset", shape, null, collection);
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Subset cannot be performed without a TraversalPolicy");

		this.pos = pos;
		init();
	}

	/**
	 * Creates a subset computation with dynamic positions provided by another Producer.
	 * This constructor enables runtime-computed positions, where the subset location
	 * is determined by the values in another collection.
	 *
	 * <p>Example usage:</p>
	 * <pre>{@code
	 * // Create position vector
	 * PackedCollection<?> position = new PackedCollection<>(3); // for 3D subset
	 * position.set(0, 20.0); // x-offset
	 * position.set(1, 30.0); // y-offset  
	 * position.set(2, 10.0); // z-offset
	 * 
	 * // Extract subset at dynamic position
	 * PackedCollectionSubset<PackedCollection<?>> subset = 
	 *     new PackedCollectionSubset<>(shape(5, 5, 5), collection, p(position));
	 * }</pre>
	 *
	 * @param shape The desired shape (dimensions) of the resulting subset
	 * @param collection The source collection to extract from, must implement {@link Shape}
	 * @param pos A Producer that generates position coordinates at runtime
	 * @throws IllegalArgumentException if collection doesn't implement Shape interface
	 * @throws IllegalArgumentException if position producer shape doesn't match input dimensions
	 */
	public PackedCollectionSubset(TraversalPolicy shape, Producer<?> collection, Producer<?> pos) {
		super("subset", shape, null, collection, pos);
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Subset cannot be performed without a TraversalPolicy");

		if (!shape(pos).equalsIgnoreAxis(shape(shape.getDimensions()))) {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Returns the memory length required for this computation.
	 * For subset operations, this is always 1 as we work with single elements.
	 *
	 * @return Always returns 1 for subset operations
	 */
	@Override
	public int getMemLength() { return 1; }

	/**
	 * Returns the total number of elements that will be processed during traversal.
	 * This corresponds to the total size of the output subset shape.
	 *
	 * @return The total count of elements in the subset
	 */
	@Override
	public long getCountLong() {
		return getShape().traverseEach().getCountLong();
	}

	/**
	 * Returns the number of statements needed for kernel generation.
	 * This is used internally by the code generation system.
	 *
	 * @param context The kernel structure context
	 * @return The number of statements required (same as memory length)
	 */
	@Override
	protected int getStatementCount(KernelStructureContext context) {
		return getMemLength();
	}

	/**
	 * Creates the destination collection for storing subset results.
	 * This is a specialized method that ensures the destination matches the subset shape.
	 *
	 * <p>Note: This custom destination creation should not be necessary in future versions,
	 * as indicated by the TODO comment in the original code.</p>
	 *
	 * @param len The expected length, must match the subset shape total size
	 * @return A new PackedCollection with the appropriate subset shape
	 * @throws IllegalArgumentException if len doesn't match the subset shape total size
	 */
	// TODO  This custom destination creation should not be necessary
	@Override
	public T createDestination(int len) {
		if (len != getShape().getTotalSize())
			throw new IllegalArgumentException("Subset kernel size must match subset shape (" + getShape().getTotalSize() + ")");

		return (T) new PackedCollection<>(getShape().traverseEach());
	}

	/**
	 * Projects an index from the output subset space to the input collection space.
	 * This is the core method that handles the mathematical transformation of indices
	 * to implement the subset operation.
	 *
	 * <p>The method handles both static positions (compile-time) and dynamic positions
	 * (runtime) by delegating to the appropriate TraversalPolicy subset method.</p>
	 *
	 * @param index The index in the output subset coordinate system
	 * @return The corresponding index in the input collection coordinate system
	 */
	@Override
	protected Expression projectIndex(Expression index) {
		TraversalPolicy inputShape = ((Shape) getInputs().get(1)).getShape();

		Expression<?> p;

		if (pos == null) {
			Expression pos[] = new Expression[inputShape.getDimensions()];
			for (int i = 0; i < pos.length; i++)
				pos[i] = getCollectionArgumentVariable(2).getValueAt(e(i)).toInt();

			p = inputShape.subset(getShape(), index, pos);
		} else {
			p = inputShape.subset(getShape(), index, pos);
		}

		return p;
	}

	/**
	 * Generates a new instance of this computation with different child processes.
	 * This method is used internally by the computation graph system for optimization
	 * and transformation purposes.
	 *
	 * @param children The new child processes to use
	 * @return A new PackedCollectionSubset instance with the updated children
	 * @throws UnsupportedOperationException if an unsupported number of children is provided
	 */
	@Override
	public PackedCollectionSubset<T> generate(List<Process<?, ?>> children) {
		if (getChildren().size() == 3) {
			return new PackedCollectionSubset<>(getShape(), (Producer<?>) children.get(1), (Producer<?>) children.get(2));
		} else if (getChildren().size() == 2) {
			return new PackedCollectionSubset<>(getShape(), (Producer<?>) children.get(1), pos);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null || pos == null)
			return signature;

		return signature + "{" + Stream.of(pos).map(Expression::signature)
						.collect(Collectors.joining("[%]")) + "}";
	}
}
