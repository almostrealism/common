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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Strategy interface for computing similarity between two vectors.
 * Implementations must be symmetric:
 * {@code similarity(a, b) == similarity(b, a)}.
 *
 * <p>Higher values indicate greater similarity. The default implementation
 * uses cosine similarity on pre-normalized vectors (i.e., dot product).</p>
 *
 * <p>Both operations describe a computation rather than performing one, so a caller
 * that scores many pairs composes them into a single graph instead of paying for a
 * separate one per pair. Evaluation belongs to the caller, at the point it actually
 * needs a number.</p>
 *
 * @see HnswIndex
 */
public interface SimilarityMetric extends MatrixFeatures {

	/**
	 * Describes the similarity between two vectors of the same dimension.
	 *
	 * @param a first vector
	 * @param b second vector
	 * @return a producer for the similarity score, higher being more similar
	 */
	CollectionProducer similarity(Producer<PackedCollection> a, Producer<PackedCollection> b);

	/**
	 * Describes the similarities between one query vector and a whole batch of
	 * candidate vectors at once, producing one score per candidate. A caller
	 * that scores a vector against many others uses this form so the entire
	 * comparison is a single computation rather than one per pair.
	 *
	 * @param candidates a {@code [count, dimension]} batch of vectors
	 * @param query      a single vector of the same dimension
	 * @return a producer for the {@code [count]} similarity scores, higher
	 *         being more similar
	 */
	CollectionProducer similarities(Producer<PackedCollection> candidates,
									Producer<PackedCollection> query);

	/**
	 * Describes a vector normalized so that similarity computations are correct.
	 * For cosine similarity this is L2 normalization, applied once at insertion.
	 *
	 * @param vector the vector to normalize
	 * @return a producer for the normalized vector
	 */
	CollectionProducer normalize(Producer<PackedCollection> vector);

	/**
	 * Cosine similarity metric. Vectors are L2-normalized on insert,
	 * so similarity reduces to a dot product.
	 */
	SimilarityMetric COSINE = new SimilarityMetric() {
		/** Leaves a vector with no magnitude at zero rather than dividing by it. */
		private static final double MIN_NORM = 1e-12;

		@Override
		public CollectionProducer similarity(Producer<PackedCollection> a,
											 Producer<PackedCollection> b) {
			return sum(multiply(a, b));
		}

		@Override
		public CollectionProducer similarities(Producer<PackedCollection> candidates,
											   Producer<PackedCollection> query) {
			return matmul(candidates, query);
		}

		@Override
		public CollectionProducer normalize(Producer<PackedCollection> vector) {
			return divide(vector, max(sqrt(sum(sq(vector))), c(MIN_NORM)));
		}
	};
}
