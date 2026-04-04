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

package org.almostrealism.audio.similarity;

import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a fast approximate similarity index over audio samples by reducing
 * each sample's multi-frame feature data to a compact embedding vector.
 *
 * <p>The index works in two steps:</p>
 * <ol>
 *   <li><b>Embedding extraction:</b> Each sample's feature tensor (frames x bins)
 *       is mean-pooled across frames to produce a single bins-dimensional vector
 *       that captures the average spectral character.</li>
 *   <li><b>Optional random projection:</b> For high-dimensional embeddings, a
 *       Gaussian random projection reduces dimensionality while preserving
 *       angular distances (Johnson-Lindenstrauss lemma).</li>
 * </ol>
 *
 * <p>Approximate cosine similarity between samples is computed as the dot product
 * of L2-normalized embedding vectors. This serves as a fast filter: pairs with low
 * approximate similarity are unlikely to have high exact per-frame similarity,
 * allowing expensive exact comparisons to be skipped.</p>
 *
 * <p>For a library of N samples, the full N*(N-1)/2 approximate comparisons run
 * in O(N^2 * d) time where d is the embedding dimension (typically 32-40). This
 * is orders of magnitude faster than exact per-frame cosine similarity, which
 * requires O(N^2 * frames * bins) work plus kernel launch overhead.</p>
 *
 * @see IncrementalSimilarityComputation
 */
public class ApproximateSimilarityIndex {

	/** Random seed used for reproducible random projections. */
	private static final long DEFAULT_SEED = 42L;

	/** Ordered list of audio identifiers corresponding to rows in the embedding matrix. */
	private final List<String> identifiers;

	/** 2D embedding matrix (identifiers.size() x dimensions) for approximate similarity. */
	private final double[][] embeddings;

	/**
	 * Creates an approximate similarity index from the given wave details.
	 *
	 * @param details the samples to index
	 * @param projectionDimensions number of random projection dimensions,
	 *        or 0 to skip projection and use raw mean-pooled embeddings
	 */
	public ApproximateSimilarityIndex(List<WaveDetails> details, int projectionDimensions) {
		this.identifiers = new ArrayList<>(details.size());
		this.embeddings = new double[details.size()][];

		int maxBins = 0;
		for (int i = 0; i < details.size(); i++) {
			WaveDetails d = details.get(i);
			identifiers.add(d.getIdentifier());
			double[] embedding = computeEmbedding(d.getFeatureData());
			embeddings[i] = embedding;
			if (embedding != null) {
				maxBins = Math.max(maxBins, embedding.length);
			}
		}

		if (projectionDimensions > 0 && maxBins > 0) {
			double[][] projectionMatrix = createProjectionMatrix(
					maxBins, projectionDimensions, DEFAULT_SEED);
			for (int i = 0; i < embeddings.length; i++) {
				if (embeddings[i] != null) {
					embeddings[i] = project(embeddings[i], projectionMatrix);
				}
			}
		}

		for (int i = 0; i < embeddings.length; i++) {
			if (embeddings[i] != null) {
				normalize(embeddings[i]);
			}
		}
	}

	/**
	 * Returns the number of indexed samples.
	 */
	public int size() {
		return identifiers.size();
	}

	/**
	 * Returns the identifier at the given index.
	 */
	public String getIdentifier(int index) {
		return identifiers.get(index);
	}

	/**
	 * Computes approximate cosine similarity between two indexed samples.
	 *
	 * @param i index of the first sample
	 * @param j index of the second sample
	 * @return approximate cosine similarity in [-1, 1], or
	 *         {@code -Double.MAX_VALUE} if either sample has no feature data
	 */
	public double approximateSimilarity(int i, int j) {
		double[] a = embeddings[i];
		double[] b = embeddings[j];
		if (a == null || b == null) return -Double.MAX_VALUE;
		return dot(a, b);
	}

	/**
	 * Finds all pairs whose approximate similarity exceeds the given threshold.
	 *
	 * @param threshold minimum approximate cosine similarity to include
	 * @return list of index pairs {@code [i, j]} with {@code i < j}
	 */
	public List<int[]> findCandidatePairs(double threshold) {
		List<int[]> pairs = new ArrayList<>();
		int n = embeddings.length;
		for (int i = 0; i < n; i++) {
			if (embeddings[i] == null) continue;
			for (int j = i + 1; j < n; j++) {
				if (embeddings[j] == null) continue;
				if (dot(embeddings[i], embeddings[j]) >= threshold) {
					pairs.add(new int[]{i, j});
				}
			}
		}
		return pairs;
	}

	/**
	 * Returns the total number of valid (non-null embedding) pairs.
	 */
	public long totalValidPairs() {
		long validCount = 0;
		for (double[] embedding : embeddings) {
			if (embedding != null) validCount++;
		}
		return validCount * (validCount - 1) / 2;
	}

	/**
	 * Computes a compact embedding by mean-pooling across feature frames.
	 *
	 * @param featureData feature tensor of shape (frames, bins) or (frames, bins, 1)
	 * @return mean-pooled embedding of length bins, or null if data is unavailable
	 */
	static double[] computeEmbedding(PackedCollection featureData) {
		if (featureData == null) return null;

		int frames = featureData.getShape().length(0);
		int bins = featureData.getShape().length(1);
		if (frames == 0 || bins == 0) return null;

		double[] embedding = new double[bins];
		double[] raw = featureData.doubleStream().toArray();

		for (int f = 0; f < frames; f++) {
			for (int b = 0; b < bins; b++) {
				int idx = f * bins + b;
				if (idx < raw.length) {
					embedding[b] += raw[idx];
				}
			}
		}

		double invFrames = 1.0 / frames;
		for (int b = 0; b < bins; b++) {
			embedding[b] *= invFrames;
		}

		return embedding;
	}

	/**
	 * Creates a Gaussian random projection matrix scaled by
	 * {@code 1/sqrt(outputDimensions)} for approximate distance preservation.
	 */
	private static double[][] createProjectionMatrix(
			int inputDimensions, int outputDimensions, long seed) {
		Random rng = new Random(seed);
		double scale = 1.0 / Math.sqrt(outputDimensions);
		double[][] matrix = new double[outputDimensions][inputDimensions];
		for (int i = 0; i < outputDimensions; i++) {
			for (int j = 0; j < inputDimensions; j++) {
				matrix[i][j] = rng.nextGaussian() * scale;
			}
		}
		return matrix;
	}

	/**
	 * Projects a vector through the random projection matrix.
	 */
	private static double[] project(double[] input, double[][] projectionMatrix) {
		int outputDim = projectionMatrix.length;
		int inputDim = projectionMatrix[0].length;
		double[] output = new double[outputDim];
		int len = Math.min(input.length, inputDim);
		for (int i = 0; i < outputDim; i++) {
			double sum = 0;
			for (int j = 0; j < len; j++) {
				sum += projectionMatrix[i][j] * input[j];
			}
			output[i] = sum;
		}
		return output;
	}

	/**
	 * Normalizes a vector to unit length in-place.
	 */
	static void normalize(double[] v) {
		double norm = 0;
		for (double x : v) {
			norm += x * x;
		}
		norm = Math.sqrt(norm);
		if (norm > 0) {
			double invNorm = 1.0 / norm;
			for (int i = 0; i < v.length; i++) {
				v[i] *= invNorm;
			}
		}
	}

	/**
	 * Computes dot product between two vectors.
	 */
	static double dot(double[] a, double[] b) {
		double sum = 0;
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++) {
			sum += a[i] * b[i];
		}
		return sum;
	}
}
