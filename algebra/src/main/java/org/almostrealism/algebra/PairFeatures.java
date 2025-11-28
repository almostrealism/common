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

package org.almostrealism.algebra;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ComplexProductExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Provides convenient factory methods for creating {@link Pair} computations and complex number operations.
 *
 * <p>
 * {@link PairFeatures} extends {@link CollectionFeatures} to provide specialized methods for working
 * with pairs and complex numbers in the computation graph framework. This interface is designed to
 * be mixed into classes that need to create pair and complex number computations.
 * </p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * public class PairComputation implements PairFeatures {
 *     public Producer<Pair> compute() {
 *         // Create constant pairs
 *         CollectionProducer<Pair> p1 = pair(3.0, 4.0);
 *         CollectionProducer<Pair> p2 = value(new Pair(1.0, 2.0));
 *
 *         // Component extraction
 *         CollectionProducer<PackedCollection> left = l(p1);   // 3.0
 *         CollectionProducer<PackedCollection> right = r(p1);  // 4.0
 *
 *         // Dynamic pair from components
 *         return pair(left, right);
 *     }
 * }
 * }</pre>
 *
 * <h2>Complex Number Operations</h2>
 * <pre>{@code
 * // Create complex numbers from real/imaginary parts
 * CollectionProducer<PackedCollection> real = c(3.0);
 * CollectionProducer<PackedCollection> imag = c(4.0);
 * CollectionProducer<Pair> complex = complexFromParts(real, imag);  // 3 + 4i
 *
 * // Complex multiplication: (a + bi)(c + di) = (ac - bd) + (ad + bc)i
 * CollectionProducer<Pair> c1 = pair(3.0, 4.0);  // 3 + 4i
 * CollectionProducer<Pair> c2 = pair(1.0, 2.0);  // 1 + 2i
 * CollectionProducerComputationBase result = multiplyComplex(c1, c2);  // -5 + 10i
 * }</pre>
 *
 * @author  Michael Murray
 * @see Pair
 * @see ComplexNumber
 * @see CollectionFeatures
 * @see CollectionProducer
 */
public interface PairFeatures extends CollectionFeatures {

	/**
	 * Creates a {@link CollectionProducer} for a constant pair from two values.
	 *
	 * @param x  the first value (left/a component)
	 * @param y  the second value (right/b component)
	 * @return a producer for the constant pair (x, y)
	 */
	default CollectionProducer<Pair> pair(double x, double y) { return value(new Pair(x, y)); }

	/**
	 * Creates a {@link CollectionProducer} for a dynamic pair by concatenating two component producers.
	 * Each component producer should produce a single scalar value.
	 *
	 * @param x  producer for the first component
	 * @param y  producer for the second component
	 * @return a producer that combines the two components into a pair
	 */
	default CollectionProducer<Pair> pair(Producer<PackedCollection> x,
											 Producer<PackedCollection> y) {
		return concat(shape(2), x, y);
	}

	/**
	 * Short form of {@link #value(Pair)}.
	 *
	 * @param value  the pair value
	 * @return a producer for the constant pair
	 */
	default CollectionProducer<Pair> v(Pair value) { return value(value); }

	/**
	 * Creates a {@link CollectionProducer} that produces a constant {@link Pair} value.
	 * This method creates a computation that returns the values from the provided {@link Pair},
	 * effectively creating a constant computation that always returns the same values.
	 *
	 * @param value  the {@link Pair} containing the constant values
	 * @return a {@link CollectionProducer} that evaluates to the specified {@link Pair}
	 */
	default CollectionProducer<Pair> value(Pair value) {
		return DefaultTraversableExpressionComputation.fixed((Pair) value, Pair.postprocessor());
	}

	/**
	 * Extracts the left/first/a component from a pair producer.
	 *
	 * @param p  the pair producer
	 * @return a producer for the left component
	 */
	default CollectionProducer<PackedCollection> l(Producer<Pair> p) {
		return subset(shape(1), p, 0);
	}

	/**
	 * Extracts the right/second/b component from a pair producer.
	 *
	 * @param p  the pair producer
	 * @return a producer for the right component
	 */
	default CollectionProducer<PackedCollection> r(Producer<Pair> p) {
		return subset(shape(1), p, 1);
	}

	/**
	 * Performs complex number multiplication: (a + bi)(c + di) = (ac - bd) + (ad + bc)i.
	 *
	 * <p>
	 * This method treats each {@link Pair} as a complex number where the first component
	 * is the real part and the second component is the imaginary part. The multiplication
	 * follows the standard complex multiplication formula.
	 * </p>
	 *
	 * @param a  the first complex number (as a Pair)
	 * @param b  the second complex number (as a Pair)
	 * @param <T>  the collection type
	 * @return a computation that produces the complex product
	 * @throws IllegalArgumentException if the collections have incompatible sizes
	 */
	default <T extends PackedCollection> CollectionProducerComputationBase<T, T> multiplyComplex(Producer<T> a, Producer<T> b) {
		TraversalPolicy shape = shape(a);
		int size = shape(b).getSize();

		if (shape.getSize() != size) {
			if (shape.getSize() != 1 && size != 1) {
				throw new IllegalArgumentException("Cannot multiply a collection of size " + shape.getSize() +
						" with a collection of size " + size);
			} else {
				// TODO This should actually just call traverseEach if the shapes don't match, but one size is = 1
				System.out.println("WARN: Multiplying a collection of size " + shape.getSize() +
						" with a collection of size " + size + " (will broadcast)");
			}
		}

		return new DefaultTraversableExpressionComputation<>("multiplyComplex", shape,
				(Function<TraversableExpression[], CollectionExpression>)
						args -> new ComplexProductExpression(shape, args[1], args[2]),
				(Producer) a, (Producer) b).setPostprocessor(ComplexNumber.complexPostprocessor());
	}

	/**
	 * Creates complex numbers from separate real and imaginary component producers.
	 * The result is a collection of {@link Pair} objects where each pair represents a complex number.
	 *
	 * @param real  producer for the real components
	 * @param imag  producer for the imaginary components
	 * @return a producer for the complex numbers (as Pairs)
	 * @throws IllegalArgumentException if the real and imaginary collections have different sizes
	 */
	default CollectionProducer<PackedCollection> complexFromParts(Producer<PackedCollection> real,
													     Producer<PackedCollection> imag) {
		long size = shape(real).getTotalSizeLong();
		if (shape(imag).getTotalSizeLong() != size) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy shape = shape(real).traverseEach().append(shape(2));
		real = reshape(shape(real).appendDimension(1), real);
		imag = reshape(shape(imag).appendDimension(1), imag);
		return concat(shape, real, imag);
	}

	/**
	 * Extracts a {@link Pair} from a pair bank at the specified dynamic index.
	 * The pair is stored in the bank as 2 consecutive values starting at position (2 * index).
	 *
	 * @param bank  the packed collection containing multiple pairs
	 * @param index  producer for the index of the pair to extract
	 * @return a producer for the pair at the specified index
	 */
	default Producer<PackedCollection> pairFromBank(Producer<PackedCollection> bank, Producer<PackedCollection> index) {
		int count = shape(index).getCount();
		Producer<PackedCollection> pair =
				add(repeat(2, traverse(1, index)).multiply(2), repeat(count, c(0.0, 1.0)));
		return (Producer) c(shape(index).append(shape(2)), bank, pair);
	}

	/**
	 * Returns a singleton instance of {@link PairFeatures}.
	 *
	 * @return a PairFeatures instance
	 */
	static PairFeatures getInstance() {
		return new PairFeatures() { };
	}
}
