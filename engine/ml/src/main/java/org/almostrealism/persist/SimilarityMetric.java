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

package org.almostrealism.persist;

import org.almostrealism.collect.PackedCollection;

/**
 * Strategy interface for computing similarity between two {@link PackedCollection}
 * vectors. Implementations must be symmetric:
 * {@code similarity(a, b) == similarity(b, a)}.
 *
 * <p>Higher values indicate greater similarity. The default implementation
 * uses cosine similarity on pre-normalized vectors (i.e., dot product).</p>
 */
public interface SimilarityMetric {

	/**
	 * Compute the similarity between two vectors of the same dimension.
	 *
	 * @param a first vector
	 * @param b second vector
	 * @return similarity score (higher is more similar)
	 */
	float similarity(PackedCollection a, PackedCollection b);

	/**
	 * Compute the similarity between two vectors given their cached
	 * double-array representations. Used by {@link HnswIndex} internally
	 * to avoid repeated native memory reads during graph traversal.
	 *
	 * <p>The default implementation delegates to
	 * {@link #similarity(PackedCollection, PackedCollection)} by wrapping
	 * the arrays. Implementations should override for performance.</p>
	 *
	 * @param a first vector data
	 * @param b second vector data
	 * @return similarity score
	 */
	default float similarityCached(double[] a, double[] b) {
		PackedCollection pcA = new PackedCollection(a.length).fill(a);
		PackedCollection pcB = new PackedCollection(b.length).fill(b);
		try {
			return similarity(pcA, pcB);
		} finally {
			pcA.destroy();
			pcB.destroy();
		}
	}

	/**
	 * Normalize a vector so that similarity computations are correct.
	 * For cosine similarity, this means L2 normalization. Called once
	 * at insertion time.
	 *
	 * @param vector the vector to normalize
	 * @return a normalized copy of the vector
	 */
	PackedCollection normalize(PackedCollection vector);

	/**
	 * Normalize a vector and return the result as a {@code double[]}
	 * without retaining a {@link PackedCollection}. This avoids native
	 * memory allocation in hot paths where only the raw data is needed.
	 *
	 * <p>The default implementation delegates to {@link #normalize} and
	 * immediately destroys the temporary collection.</p>
	 *
	 * @param vector the vector to normalize
	 * @return the normalized data as a double array
	 */
	default double[] normalizeToArray(PackedCollection vector) {
		PackedCollection normalized = normalize(vector);
		double[] data = toDoubleArray(normalized);
		normalized.destroy();
		return data;
	}

	/**
	 * Cosine similarity metric. Vectors are L2-normalized on insert,
	 * so similarity reduces to a dot product.
	 */
	SimilarityMetric COSINE = new SimilarityMetric() {
		@Override
		public float similarity(PackedCollection a, PackedCollection b) {
			return similarityCached(toDoubleArray(a), toDoubleArray(b));
		}

		@Override
		public float similarityCached(double[] a, double[] b) {
			double dot = 0.0;
			for (int i = 0; i < a.length; i++) {
				dot += a[i] * b[i];
			}
			return (float) dot;
		}

		@Override
		public PackedCollection normalize(PackedCollection vector) {
			double[] data = normalizeToArray(vector);
			return new PackedCollection(data.length).fill(data);
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
