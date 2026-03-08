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
import org.almostrealism.audio.data.WaveDetailsFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes pairwise audio similarity incrementally using a two-phase approach:
 * approximate filtering followed by exact computation for promising pairs only.
 *
 * <p>The full N*(N-1)/2 pairwise similarity computation is expensive for large
 * libraries. This class reduces the work by first computing fast approximate
 * similarity using {@link ApproximateSimilarityIndex}, then only computing
 * exact per-frame cosine similarity for pairs that exceed an approximate
 * similarity threshold. Typically this eliminates 80-90% of exact comparisons
 * while preserving the pairs that matter most for community detection.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Build an {@link ApproximateSimilarityIndex} from mean-pooled feature
 *       embeddings (O(N * frames * bins) for embedding, O(N^2 * bins) for
 *       all-pairs approximate similarity)</li>
 *   <li>Identify candidate pairs where approximate similarity exceeds the
 *       threshold</li>
 *   <li>Compute exact per-frame cosine similarity for candidate pairs using
 *       {@link WaveDetailsFactory#batchSimilarity(WaveDetails, List)}</li>
 *   <li>Store exact results bidirectionally in each sample's
 *       {@link WaveDetails#getSimilarities()} map</li>
 * </ol>
 *
 * <p>Pairs already present in the similarity maps are skipped, making this
 * computation safe to call incrementally as new samples are added.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * List<WaveDetails> allDetails = new ArrayList<>(library.getAllDetails());
 * IncrementalSimilarityComputation computation =
 *     new IncrementalSimilarityComputation(factory, allDetails, 0.3);
 * IncrementalSimilarityComputation.Result result = computation.compute();
 * // result.reductionPercent() -> e.g., 85.0 (85% of pairs skipped)
 * }</pre>
 *
 * @see ApproximateSimilarityIndex
 * @see WaveDetailsFactory
 */
public class IncrementalSimilarityComputation {

	/** Default approximate similarity threshold for filtering. */
	public static final double DEFAULT_THRESHOLD = 0.3;

	/** Default projection dimensions (0 = no projection, use raw embeddings). */
	public static final int DEFAULT_PROJECTION_DIMENSIONS = 0;

	private final WaveDetailsFactory factory;
	private final List<WaveDetails> details;
	private final double threshold;
	private final int projectionDimensions;

	/**
	 * Creates an incremental similarity computation with default projection settings.
	 *
	 * @param factory the factory for exact similarity computation
	 * @param details all samples to compare
	 * @param threshold minimum approximate similarity for exact computation
	 */
	public IncrementalSimilarityComputation(WaveDetailsFactory factory,
											List<WaveDetails> details,
											double threshold) {
		this(factory, details, threshold, DEFAULT_PROJECTION_DIMENSIONS);
	}

	/**
	 * Creates an incremental similarity computation with custom projection settings.
	 *
	 * @param factory the factory for exact similarity computation
	 * @param details all samples to compare
	 * @param threshold minimum approximate similarity for exact computation
	 * @param projectionDimensions random projection dimensions, or 0 for none
	 */
	public IncrementalSimilarityComputation(WaveDetailsFactory factory,
											List<WaveDetails> details,
											double threshold,
											int projectionDimensions) {
		this.factory = factory;
		this.details = new ArrayList<>(details);
		this.threshold = threshold;
		this.projectionDimensions = projectionDimensions;
	}

	/**
	 * Executes the two-phase similarity computation.
	 *
	 * <p>Phase 1 builds the approximate index and identifies candidate pairs.
	 * Phase 2 computes exact similarity for candidates, grouped by query for
	 * efficient batched kernel execution.</p>
	 *
	 * @return result containing timing and filtering statistics
	 */
	public Result compute() {
		long startTime = System.nanoTime();

		// Phase 1: Build approximate index
		long indexStart = System.nanoTime();
		ApproximateSimilarityIndex index =
				new ApproximateSimilarityIndex(details, projectionDimensions);
		long indexTime = System.nanoTime() - indexStart;

		long totalPairs = index.totalValidPairs();

		// Phase 2: Find candidate pairs above threshold
		long filterStart = System.nanoTime();
		List<int[]> candidatePairs = index.findCandidatePairs(threshold);
		long filterTime = System.nanoTime() - filterStart;

		// Phase 3: Compute exact similarity for candidates, skipping
		// pairs that already have computed similarities
		long exactStart = System.nanoTime();
		Map<Integer, List<Integer>> targetsByQuery = new HashMap<>();
		for (int[] pair : candidatePairs) {
			WaveDetails query = details.get(pair[0]);
			WaveDetails target = details.get(pair[1]);
			if (!query.getSimilarities().containsKey(target.getIdentifier())) {
				targetsByQuery.computeIfAbsent(pair[0], k -> new ArrayList<>())
						.add(pair[1]);
			}
		}

		long exactPairs = 0;
		for (Map.Entry<Integer, List<Integer>> entry : targetsByQuery.entrySet()) {
			int queryIdx = entry.getKey();
			List<Integer> targetIndices = entry.getValue();

			WaveDetails query = details.get(queryIdx);
			List<WaveDetails> targets = new ArrayList<>(targetIndices.size());
			for (int idx : targetIndices) {
				targets.add(details.get(idx));
			}

			double[] similarities = factory.batchSimilarity(query, targets);

			for (int i = 0; i < targets.size(); i++) {
				WaveDetails target = targets.get(i);
				double similarity = similarities[i];
				query.getSimilarities().put(target.getIdentifier(), similarity);
				if (query.getIdentifier() != null) {
					target.getSimilarities().put(query.getIdentifier(), similarity);
				}
			}

			exactPairs += targets.size();
		}

		long exactTime = System.nanoTime() - exactStart;
		long totalTime = System.nanoTime() - startTime;

		return new Result(totalPairs, exactPairs,
				indexTime, filterTime, exactTime, totalTime);
	}

	/**
	 * Result of an incremental similarity computation, providing statistics
	 * about the approximate filtering effectiveness and timing breakdown.
	 */
	public static class Result {
		private final long totalPairs;
		private final long exactPairs;
		private final long indexTimeNanos;
		private final long filterTimeNanos;
		private final long exactTimeNanos;
		private final long totalTimeNanos;

		Result(long totalPairs, long exactPairs,
			   long indexTimeNanos, long filterTimeNanos,
			   long exactTimeNanos, long totalTimeNanos) {
			this.totalPairs = totalPairs;
			this.exactPairs = exactPairs;
			this.indexTimeNanos = indexTimeNanos;
			this.filterTimeNanos = filterTimeNanos;
			this.exactTimeNanos = exactTimeNanos;
			this.totalTimeNanos = totalTimeNanos;
		}

		/** Total number of valid pairs (N*(N-1)/2). */
		public long totalPairs() { return totalPairs; }

		/** Number of pairs that received exact similarity computation. */
		public long exactPairs() { return exactPairs; }

		/** Number of pairs skipped (below approximate threshold). */
		public long skippedPairs() { return totalPairs - exactPairs; }

		/** Percentage of pairs that were skipped (0-100). */
		public double reductionPercent() {
			return totalPairs == 0 ? 0 : 100.0 * skippedPairs() / totalPairs;
		}

		/** Time spent building the approximate index, in nanoseconds. */
		public long indexTimeNanos() { return indexTimeNanos; }

		/** Time spent finding candidate pairs, in nanoseconds. */
		public long filterTimeNanos() { return filterTimeNanos; }

		/** Time spent on exact similarity computation, in nanoseconds. */
		public long exactTimeNanos() { return exactTimeNanos; }

		/** Total wall-clock time, in nanoseconds. */
		public long totalTimeNanos() { return totalTimeNanos; }
	}
}
