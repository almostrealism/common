/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.heredity;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A feature interface providing factory methods for creating genetic data structures.
 *
 * <p>This interface provides convenient shorthand methods for creating genes and chromosomes,
 * as well as utility methods for value transformations commonly used in genetic algorithms.
 *
 * <h2>Factory Methods</h2>
 * <ul>
 *   <li>{@link #g(double...)} - Create a gene from scalar values</li>
 *   <li>{@link #g(Producer[])} - Create a gene from producer values</li>
 *   <li>{@link #g(Factor[])} - Create a gene from factors</li>
 *   <li>{@link #c(Gene[])} - Create a chromosome from genes</li>
 *   <li>{@link #chromosome(List)} - Create a chromosome from a gene list</li>
 * </ul>
 *
 * <h2>Transformation Methods</h2>
 * <ul>
 *   <li>{@link #oneToInfinity(double, double)} - Map [0,1) to [0, infinity)</li>
 *   <li>{@link #invertOneToInfinity(double, double, double)} - Inverse of oneToInfinity</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * HeredityFeatures features = HeredityFeatures.getInstance();
 *
 * // Create genes from scalar values
 * Gene<PackedCollection<?>> gene1 = features.g(0.1, 0.5, 0.9);
 * Gene<PackedCollection<?>> gene2 = features.g(-1.0, 0.0, 1.0);
 *
 * // Create chromosome from genes
 * Chromosome<PackedCollection<?>> chromosome = features.c(gene1, gene2);
 *
 * // Access factors
 * Factor<PackedCollection<?>> factor = chromosome.valueAt(0, 1);
 *
 * // Transform value from [0,1) to [0, infinity)
 * double expanded = features.oneToInfinity(0.9, 2.0);  // Returns a large value
 * }</pre>
 *
 * @see Gene
 * @see Chromosome
 * @see ScaleFactor
 */
public interface HeredityFeatures extends CollectionFeatures {
	/**
	 * Creates a chromosome from a list of genes.
	 *
	 * @param genes the list of genes to include in the chromosome
	 * @return a new chromosome containing the specified genes
	 */
	default Chromosome<PackedCollection<?>> chromosome(List<? extends Gene<PackedCollection<?>>> genes) {
		return new Chromosome<>() {
			@Override
			public int length() {
				return genes.size();
			}

			@Override
			public Gene<PackedCollection<?>> valueAt(int pos) {
				return genes.get(pos);
			}
		};
	}

	/**
	 * Creates a chromosome from a varargs array of genes.
	 * <p>This is a shorthand method for quickly creating chromosomes.
	 *
	 * @param genes the genes to include in the chromosome
	 * @return a new chromosome containing the specified genes
	 */
	default Chromosome<PackedCollection<?>> c(Gene<PackedCollection<?>>... genes) {
		return new Chromosome<>() {
			@Override
			public int length() {
				return genes.length;
			}

			@Override
			public Gene<PackedCollection<?>> valueAt(int pos) {
				return genes[pos];
			}
		};
	}

	/**
	 * Creates a gene from scalar values.
	 * <p>Each value becomes a {@link ScaleFactor} in the resulting gene.
	 *
	 * @param factors the scalar values for each factor
	 * @return a new gene with ScaleFactor instances at each position
	 */
	default Gene<PackedCollection<?>> g(double... factors) {
		return g(IntStream.range(0, factors.length).mapToObj(i -> new ScaleFactor(factors[i])).toArray(Factor[]::new));
	}

	/**
	 * Creates a gene from scalar producers.
	 * <p>Each producer is wrapped in a factor that ignores input and returns the producer's value.
	 *
	 * @param factors the scalar producers for each factor
	 * @return a new gene with the specified producers as factors
	 */
	default Gene<PackedCollection<?>> g(Producer<Scalar>... factors) {
		return g(Stream.of(factors).map(f -> (Factor) protein -> f).toArray(Factor[]::new));
	}

	/**
	 * Creates a gene from an array of factors.
	 * <p>This is the most general gene creation method.
	 *
	 * @param factors the factors to include in the gene
	 * @return a new gene containing the specified factors
	 */
	default Gene<PackedCollection<?>> g(Factor<PackedCollection<?>>... factors) {
		return new Gene<PackedCollection<?>>() {
			@Override
			public int length() {
				return factors.length;
			}

			@Override
			public Factor<PackedCollection<?>> valueAt(int pos) {
				return factors[pos];
			}
		};
	}

	/**
	 * Computes the inverse of the oneToInfinity transformation.
	 * <p>Given a target value in [0, infinity), returns the corresponding value in [0, 1).
	 *
	 * @param target the target value (from oneToInfinity output)
	 * @param multiplier a scaling factor applied to the target
	 * @param exp the exponent used in the transformation
	 * @return the original value in [0, 1) that would produce the target
	 */
	default double invertOneToInfinity(double target, double multiplier, double exp) {
		return Math.pow(1 - (1 / ((target / multiplier) + 1)), 1.0 / exp);
	}

	/**
	 * Transforms a value from [0, 1) to [0, infinity) using an exponential formula.
	 * <p>Formula: {@code 1 / (1 - f^exp) - 1}
	 * <p>As f approaches 1, the output approaches infinity.
	 *
	 * @param f the input value in [0, 1)
	 * @param exp the exponent controlling the expansion rate
	 * @return the transformed value in [0, infinity)
	 */
	default double oneToInfinity(double f, double exp) {
		return 1.0 / (1.0 - Math.pow(f, exp)) - 1.0;
	}

	/**
	 * Creates a producer that applies the oneToInfinity transformation to a factor's output.
	 *
	 * @param f the factor to transform (evaluated with input 1.0)
	 * @param exp the exponent for the transformation
	 * @return a producer that computes the transformed value
	 */
	default CollectionProducer<PackedCollection<?>> oneToInfinity(Factor<PackedCollection<?>> f, double exp) {
		return oneToInfinity(f.getResultant(c(1.0)), exp);
	}

	/**
	 * Creates a producer that applies the oneToInfinity transformation to a producer's output.
	 *
	 * @param arg the input producer
	 * @param exp the exponent for the transformation
	 * @return a producer that computes the transformed value
	 */
	default CollectionProducer<PackedCollection<?>> oneToInfinity(Producer<PackedCollection<?>> arg, double exp) {
		return oneToInfinity(arg, c(exp));
	}

	/**
	 * Creates a producer that applies the oneToInfinity transformation using producer-based arguments.
	 * <p>Formula: {@code 1 / (1 - arg^exp) - 1}
	 *
	 * @param arg the input producer (values should be in [0, 1))
	 * @param exp the exponent producer
	 * @return a producer that computes the transformed value
	 */
	default CollectionProducer<PackedCollection<?>> oneToInfinity(Producer<PackedCollection<?>> arg, Producer<PackedCollection<?>> exp) {
		CollectionProducer<PackedCollection<?>> pow = pow(arg, exp);
		CollectionProducer<PackedCollection<?>> out = minus(pow);
		out = add(out, c(1.0));
		out = pow(out, c(-1.0));
		out = add(out, c(-1.0));
		return out;
	}

	/**
	 * Returns a singleton instance of HeredityFeatures.
	 * <p>Use this when you need access to the factory methods without implementing the interface.
	 *
	 * @return a HeredityFeatures instance
	 */
	static HeredityFeatures getInstance() {
		return new HeredityFeatures() { };
	}
}
