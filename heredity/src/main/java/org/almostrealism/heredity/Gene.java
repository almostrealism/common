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
import io.almostrealism.uml.Plural;

import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Represents a single genetic unit containing a collection of {@link Factor}s.
 *
 * <p>A {@code Gene} is the fundamental unit of genetic information in this framework.
 * It contains one or more factors, where each factor transforms an input producer into
 * an output producer. Genes can be combined into {@link Chromosome}s, which in turn
 * form {@link Genome}s.
 *
 * <p>The generic type parameter {@code T} represents the data type that factors operate on,
 * typically {@code PackedCollection<?>} for numerical computations.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create a gene with scalar factors
 * Gene<PackedCollection<?>> gene = HeredityFeatures.getInstance().g(0.1, 0.5, 0.9);
 *
 * // Access individual factors
 * Factor<PackedCollection<?>> factor = gene.valueAt(0);
 *
 * // Apply factor to input
 * Producer<PackedCollection<?>> result = gene.getResultant(0, inputProducer);
 *
 * // Get gene signature for identification
 * String sig = gene.signature();
 * }</pre>
 *
 * @param <T> the type of data that factors in this gene operate on
 * @see Chromosome
 * @see Genome
 * @see Factor
 * @see HeredityFeatures#g(double...)
 */
public interface Gene<T> extends Plural<Factor<T>>, IntFunction<Factor<T>> {
	/**
	 * Returns the factor at the specified position.
	 * <p>This is the {@link IntFunction} implementation that delegates to {@link #valueAt(int)}.
	 *
	 * @param pos the zero-based position of the factor to retrieve
	 * @return the factor at the specified position
	 */
	@Override
	default Factor<T> apply(int pos) { return valueAt(pos); }

	/**
	 * Applies the factor at the specified position to the input producer and returns the result.
	 * <p>This is a convenience method that combines factor retrieval and application.
	 *
	 * @param pos the zero-based position of the factor to use
	 * @param input the input producer to transform
	 * @return a producer representing the transformed result
	 */
	default Producer<T> getResultant(int pos, Producer<T> input) {
		return valueAt(pos).getResultant(input);
	}

	/**
	 * Returns the number of factors in this gene.
	 *
	 * @return the number of factors
	 */
	int length();

	/**
	 * Generates a unique signature string for this gene by concatenating the
	 * signatures of all contained factors.
	 * <p>This can be used for identification, comparison, or hashing purposes
	 * in evolutionary algorithms.
	 *
	 * @return a string representing the combined signatures of all factors
	 */
	default String signature() {
		StringBuffer buf = new StringBuffer();
		IntStream.range(0, length()).mapToObj(this::valueAt).map(Factor::signature).forEach(buf::append);
		return buf.toString();
	}

	/**
	 * Creates a new {@code Gene} from a length and a function that provides factors by position.
	 * <p>This is a factory method for creating anonymous gene implementations.
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Gene<PackedCollection<?>> gene = Gene.of(3, pos -> new ScaleFactor(pos * 0.1));
	 * }</pre>
	 *
	 * @param <T> the type of data that factors operate on
	 * @param length the number of factors in the gene
	 * @param factor a function that returns a factor for each position
	 * @return a new gene with the specified factors
	 */
	static <T> Gene<T> of(int length, IntFunction<Factor<T>> factor) {
		return new Gene<T>() {
			@Override
			public Factor<T> valueAt(int pos) {
				return factor.apply(pos);
			}

			@Override
			public int length() {
				return length;
			}
		};
	}
}
