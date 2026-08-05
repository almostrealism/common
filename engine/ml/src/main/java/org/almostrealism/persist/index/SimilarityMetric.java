/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.persist.index;

import org.almostrealism.collect.PackedCollection;

/**
 * Strategy interface for computing similarity between two vectors.
 * Implementations must be symmetric:
 * {@code similarity(a, b) == similarity(b, a)}.
 *
 * <p>Higher values indicate greater similarity. The default implementation
 * uses cosine similarity on pre-normalized vectors (i.e., dot product).</p>
 *
 * <h2>Why this interface is expressed over arrays</h2>
 *
 * <p>{@link HnswIndex} holds each node's vector as a {@code double[]} and compares
 * one pair at a time while walking the graph. The traversal is a long sequence of
 * small, data-dependent comparisons whose next step is not known until the current
 * one is scored, so there is no batch to hand to the device and nothing to gain by
 * moving a single short dot product there. The arrays the index caches are what
 * keeps that walk off the native memory bus.</p>
 *
 * <p>The array-valued methods are therefore the primitives, and the ones that take
 * a {@link PackedCollection} read it once and delegate. Making the collection form
 * primitive instead only converts values back and forth around the same
 * arithmetic.</p>
 */
public interface SimilarityMetric {

	/**
	 * Compute the similarity between two vectors of the same dimension.
	 *
	 * @param a first vector data
	 * @param b second vector data
	 * @return similarity score (higher is more similar)
	 */
	float similarityCached(double[] a, double[] b);

	/**
	 * Normalize a vector so that similarity computations are correct.
	 * For cosine similarity, this means L2 normalization. Called once
	 * at insertion time.
	 *
	 * @param vector the vector to normalize
	 * @return the normalized data
	 */
	double[] normalizeToArray(PackedCollection vector);

	/**
	 * Compute the similarity between two vectors held as collections.
	 *
	 * @param a first vector
	 * @param b second vector
	 * @return similarity score (higher is more similar)
	 */
	default float similarity(PackedCollection a, PackedCollection b) {
		return similarityCached(toDoubleArray(a), toDoubleArray(b));
	}

	/**
	 * Normalize a vector, returning the result as a collection.
	 *
	 * @param vector the vector to normalize
	 * @return a normalized copy of the vector
	 */
	default PackedCollection normalize(PackedCollection vector) {
		return PackedCollection.of(normalizeToArray(vector));
	}

	/**
	 * Cosine similarity metric. Vectors are L2-normalized on insert,
	 * so similarity reduces to a dot product.
	 */
	SimilarityMetric COSINE = new SimilarityMetric() {
		@Override
		public float similarityCached(double[] a, double[] b) {
			double dot = 0.0;
			for (int i = 0; i < a.length; i++) {
				dot += a[i] * b[i];
			}
			return (float) dot;
		}

		@Override
		public double[] normalizeToArray(PackedCollection vector) {
			double[] data = toDoubleArray(vector);
			double norm = 0.0;
			for (double v : data) {
				norm += v * v;
			}
			norm = Math.sqrt(norm);

			if (norm > 0.0) {
				for (int i = 0; i < data.length; i++) {
					data[i] /= norm;
				}
			}
			return data;
		}
	};

	/**
	 * Extract the raw data from a {@link PackedCollection} as a double array.
	 * Provides a single bulk read from native memory, avoiding per-element
	 * JNI overhead in hot computation paths.
	 *
	 * @param collection the collection to read
	 * @return the data as a double array
	 */
	static double[] toDoubleArray(PackedCollection collection) {
		return collection.doubleStream().toArray();
	}
}
