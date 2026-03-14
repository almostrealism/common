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

/**
 * Strategy interface for computing similarity between two float vectors.
 * Implementations must be symmetric: {@code similarity(a, b) == similarity(b, a)}.
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
	float similarity(float[] a, float[] b);

	/**
	 * Normalize a vector in place so that similarity computations are
	 * correct. For cosine similarity, this means L2 normalization.
	 * Called once at insertion time.
	 *
	 * @param vector the vector to normalize in place
	 */
	void normalize(float[] vector);

	/**
	 * Cosine similarity metric. Vectors are L2-normalized on insert,
	 * so similarity reduces to a dot product.
	 */
	SimilarityMetric COSINE = new SimilarityMetric() {
		@Override
		public float similarity(float[] a, float[] b) {
			float dot = 0.0f;
			for (int i = 0; i < a.length; i++) {
				dot += a[i] * b[i];
			}
			return dot;
		}

		@Override
		public void normalize(float[] vector) {
			float norm = 0.0f;
			for (float v : vector) {
				norm += v * v;
			}
			norm = (float) Math.sqrt(norm);
			if (norm > 0.0f) {
				for (int i = 0; i < vector.length; i++) {
					vector[i] /= norm;
				}
			}
		}
	};
}
