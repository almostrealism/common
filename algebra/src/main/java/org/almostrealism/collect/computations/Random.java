/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Multiple;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryBank;

import java.util.stream.IntStream;

/**
 * A producer of random values that generates {@link PackedCollection}s filled with
 * random numbers according to a specified {@link TraversalPolicy}. This class supports
 * both uniform random distribution (default) and normal (Gaussian) distribution.
 * 
 * <p>The Random class is designed to integrate seamlessly with the AlmostRealism
 * computational framework, providing random number generation capabilities that
 * respect the shape and dimensionality requirements of the computational graph.</p>
 * 
 * <p><strong>Usage Examples:</strong></p>
 * <pre>{@code
 * // Create uniform random values with shape [3, 4]
 * TraversalPolicy shape = new TraversalPolicy(3, 4);
 * Random uniformRandom = new Random(shape);
 * PackedCollection<?> uniformValues = uniformRandom.get().evaluate();
 * 
 * // Create normal distribution random values
 * Random normalRandom = new Random(shape, true);
 * PackedCollection<?> normalValues = normalRandom.get().evaluate();
 * 
 * // Using convenience methods from CollectionFeatures
 * Producer<PackedCollection<?>> rand = rand(3, 4);  // uniform
 * Producer<PackedCollection<?>> randn = randn(3, 4); // normal
 * }</pre>
 * 
 * <p>The class maintains an internal cache of generated values that can be refreshed
 * using the {@link #refresh()} method. Values are generated lazily and cached until
 * explicitly refreshed or the object is recreated.</p>
 * 
 * <p><strong>Distribution Types:</strong></p>
 * <ul>
 *   <li><strong>Uniform:</strong> Values distributed uniformly in the range [0.0, 1.0)</li>
 *   <li><strong>Normal:</strong> Values distributed according to standard normal distribution (μ=0, σ=1)</li>
 * </ul>
 * 
 * <p>The class also provides static utility methods {@link #nextInt()} and {@link #nextFloat()}
 * that use a custom xorshift algorithm for high-performance random number generation.</p>
 * 
 * @author Michael Murray
 * @see TraversalPolicy
 * @see PackedCollection
 * @since 0.69
 */
// TODO  It seems like this should actually implement CollectionProducer
public class Random implements Producer<PackedCollection<?>>, Shape<Producer<PackedCollection<?>>>, OperationInfo {
	/** Static seed used by the xorshift random number generator in {@link #nextInt()} and {@link #nextFloat()} */
	private static long seed;

	/** Metadata describing this random generation operation */
	private OperationMetadata metadata;
	
	/** Java standard library Random instance used for generating uniform and normal distributions */
	private java.util.Random random;
	
	/** The shape (dimensions) of the random values to be generated */
	private TraversalPolicy shape;
	
	/** Flag indicating whether to generate normal distribution (true) or uniform distribution (false) */
	private boolean normal;

	/** Cached array of generated random values, null until first initialization */
	private double[] values;

	/**
	 * Creates a new Random producer with uniform distribution.
	 * This is equivalent to calling {@code new Random(shape, false)}.
	 * 
	 * @param shape the {@link TraversalPolicy} defining the dimensions and shape of random values to generate
	 * @throws IllegalArgumentException if the shape has zero total size
	 * @see #Random(TraversalPolicy, boolean)
	 */
	public Random(TraversalPolicy shape) {
		this(shape, false);
	}

	/**
	 * Creates a new Random producer with the specified distribution type.
	 * 
	 * <p>This constructor initializes the random generator with the given shape and
	 * distribution type. The shape must have a non-zero total size.</p>
	 * 
	 * @param shape the {@link TraversalPolicy} defining the dimensions and shape of random values to generate
	 * @param normal if true, generates values from a standard normal distribution (μ=0, σ=1);
	 *               if false, generates values from a uniform distribution in range [0.0, 1.0)
	 * @throws IllegalArgumentException if the shape has zero total size
	 */
	public Random(TraversalPolicy shape, boolean normal) {
		this.metadata = new OperationMetadata("Random", "Generate random values",
				"Generate random values " + shape.toStringDetail());
		this.random = new java.util.Random();
		this.shape = shape;
		this.normal = normal;

		if (shape.getTotalSizeLong() == 0) {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Returns the metadata describing this random generation operation.
	 * 
	 * @return the {@link OperationMetadata} containing operation details
	 */
	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * Initializes the random values array if it hasn't been created yet.
	 * 
	 * <p>This method generates random values according to the configured distribution:
	 * <ul>
	 *   <li>Normal distribution: uses {@link java.util.Random#nextGaussian()}</li>
	 *   <li>Uniform distribution: uses {@link java.util.Random#nextDouble()}</li>
	 * </ul>
	 * 
	 * <p>The values are cached in the internal array and will not be regenerated
	 * until {@link #refresh()} is called.</p>
	 */
	protected void initValues() {
		if (values == null) {
			values = IntStream.range(0, getShape().getTotalSize())
					.mapToDouble(i -> normal ? random.nextGaussian() : random.nextDouble())
					.toArray();
		}
	}

	/**
	 * Clears the cached random values, forcing regeneration on next access.
	 * 
	 * <p>After calling this method, the next call to {@link #get()} will trigger
	 * a fresh generation of random values.</p>
	 */
	public void refresh() { values = null; }

	/**
	 * Returns an {@link Evaluable} that produces {@link PackedCollection}s filled with random values.
	 * 
	 * <p>The returned evaluable creates destinations with the appropriate shape and fills them
	 * with random values according to the configured distribution. Values are generated lazily
	 * and cached until {@link #refresh()} is called.</p>
	 * 
	 * @return an Evaluable that generates PackedCollection instances filled with random values
	 */
	@Override
	public Evaluable<PackedCollection<?>> get() {
		return new Evaluable<>() {
			@Override
			public Multiple<PackedCollection<?>> createDestination(int size) {
				return new PackedCollection<>(getShape().prependDimension(size));
			}

			@Override
			public PackedCollection<?> evaluate(Object... args) {
				PackedCollection<?> destination = new PackedCollection<>(getShape());
				into(destination).evaluate(args);
				return destination;
			}

			@Override
			public Evaluable<PackedCollection<?>> into(Object destination) {
				return args -> {
					initValues();
					((MemoryBank) destination).setMem(values);
					return (PackedCollection<?>) destination;
				};
			}
		};
	}

	/**
	 * Returns the shape (dimensions) of the random values produced by this generator.
	 * 
	 * @return the {@link TraversalPolicy} defining the shape of generated random values
	 */
	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	/**
	 * Creates a new producer that traverses along the specified axis.
	 * 
	 * @param axis the axis along which to traverse
	 * @return a new Producer that provides traversal functionality
	 */
	@Override
	public Producer<PackedCollection<?>> traverse(int axis) {
		return new ReshapeProducer(axis, this);
	}

	/**
	 * Creates a new producer with the specified shape.
	 * 
	 * @param shape the new {@link TraversalPolicy} shape for the producer
	 * @return a new Producer with the specified shape
	 */
	@Override
	public Producer<PackedCollection<?>> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}

	// TODO  There should be an option to use this ring xor algorithm
	// TODO  (as a genuine Computation) instead of the java.util.Random

	/**
	 * Generates a pseudo-random integer using a custom xorshift algorithm.
	 * 
	 * <p>This method uses a high-performance xorshift pseudo-random number generator
	 * that operates on a static seed. The algorithm applies a series of XOR and
	 * shift operations to produce pseudo-random values.</p>
	 * 
	 * <p><strong>Note:</strong> This method is not thread-safe due to the shared static seed.
	 * For thread-safe random number generation, use instance methods with the Java
	 * {@link java.util.Random} class.</p>
	 * 
	 * @return a pseudo-random integer value
	 * @see #nextFloat()
	 */
	public static int nextInt() {
		seed ^= seed >> 12;
		seed ^= seed << 25;
		seed ^= seed >> 27;
		return (int) ((seed * 0x2545F4914F6CDD1DL) >> 32);
	}

	/**
	 * Generates a pseudo-random float in the range [0.0, 1.0) using the xorshift algorithm.
	 * 
	 * <p>This method builds on {@link #nextInt()} to produce floating-point values
	 * in the standard range for random number generation. The conversion ensures
	 * uniform distribution across the range.</p>
	 * 
	 * <p><strong>Note:</strong> This method is not thread-safe due to the shared static seed.</p>
	 * 
	 * @return a pseudo-random float value in the range [0.0, 1.0)
	 * @see #nextInt()
	 */
	public static float nextFloat() {
		return (nextInt() >>> 8) / 16777216.0f;
	}
}
