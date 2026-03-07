/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.Ops;
import org.almostrealism.util.TestDepth;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.DoubleStream;

/**
 * Simulates the pairwise comparison pattern used by PrototypeDiscovery
 * to measure Java overhead in Computation creation vs native execution.
 *
 * <p>With N tensors, this performs N*(N-1)/2 comparisons. At N=1000,
 * that is ~500K comparisons — each of which creates multiple
 * {@link org.almostrealism.collect.CollectionProducer} instances via
 * {@link CollectionFeatures} methods (multiply, sum, divide, length).
 * The goal is to isolate and measure the overhead of creating these
 * Computation objects (shape analysis, alignTraversalAxes, object
 * allocation) vs the actual native kernel execution time.</p>
 */
public class SimilarityOverheadTest extends TestSuiteBase {

	/** Number of feature bins per frame (matches typical audio feature extraction). */
	private static final int BINS = 40;

	/** Number of feature frames per entry. */
	private static final int FRAMES = 100;

	/**
	 * Runs a full pairwise similarity comparison at scale, matching
	 * the {@code WaveDetailsFactory.productSimilarity} code path.
	 *
	 * <p>This is the expensive test that should be run with JMX
	 * monitoring enabled to profile allocation and timing hotspots.</p>
	 */
	@Test(timeout = 600_000)
	@TestDepth(3)
	public void pairwiseSimilarityAtScale() {
		int count = 1000;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		long totalComparisons = 0;
		long startTime = System.nanoTime();

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				double sim = productSimilarity(
						cp(tensors[i]), cp(tensors[j]), FRAMES);
				totalComparisons++;
			}

			if (i % 100 == 0 && i > 0) {
				long elapsed = System.nanoTime() - startTime;
				double perComparison = elapsed / (double) totalComparisons;
				log("After " + totalComparisons + " comparisons (" + i +
						" of " + count + " rows): " +
						String.format("%.2f", perComparison / 1_000_000.0) +
						" ms/comparison, " +
						String.format("%.1f", elapsed / 1_000_000_000.0) + "s total");
			}
		}

		long elapsed = System.nanoTime() - startTime;
		log("Completed " + totalComparisons + " comparisons in " +
				String.format("%.1f", elapsed / 1_000_000_000.0) + "s (" +
				String.format("%.3f", elapsed / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison)");
	}

	/**
	 * Smaller-scale test (100 tensors, ~5K comparisons) that runs at
	 * default test depth to verify the comparison pipeline works and
	 * provide baseline timing data.
	 */
	@Test(timeout = 120_000)
	public void pairwiseSimilarityBaseline() {
		int count = 100;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		long totalComparisons = 0;
		long computationCreationTime = 0;
		long evaluationTime = 0;

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				long t0 = System.nanoTime();
				CollectionProducer computation = buildSimilarityComputation(
						cp(tensors[i]), cp(tensors[j]));
				long t1 = System.nanoTime();
				PackedCollection result = computation.evaluate();
				long t2 = System.nanoTime();

				computationCreationTime += (t1 - t0);
				evaluationTime += (t2 - t1);
				totalComparisons++;
			}
		}

		log("=== Baseline Results (" + totalComparisons + " comparisons) ===");
		log("Computation creation: " +
				String.format("%.1f", computationCreationTime / 1_000_000_000.0) + "s total, " +
				String.format("%.3f", computationCreationTime / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison");
		log("Evaluation (native):  " +
				String.format("%.1f", evaluationTime / 1_000_000_000.0) + "s total, " +
				String.format("%.3f", evaluationTime / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison");
		log("Creation overhead:    " +
				String.format("%.1f%%", 100.0 * computationCreationTime /
						(computationCreationTime + evaluationTime)));
	}

	/**
	 * Measures the cost of repeated {@code cp()} wrapping — converting
	 * a {@link PackedCollection} into a {@code Producer} on every comparison.
	 */
	@Test(timeout = 60_000)
	public void cpWrappingOverhead() {
		int count = 100;
		int iterations = 10_000;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		long start = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			for (int j = 0; j < count; j++) {
				cp(tensors[j]);
			}
		}
		long elapsed = System.nanoTime() - start;
		long totalCalls = (long) iterations * count;
		log("cp() wrapping: " + totalCalls + " calls in " +
				String.format("%.1f", elapsed / 1_000_000_000.0) + "s (" +
				String.format("%.0f", elapsed / (double) totalCalls) + " ns/call)");
	}

	/**
	 * Measures the cost of multiply+sum computation creation without
	 * evaluation — isolating the expression tree construction overhead.
	 */
	@Test(timeout = 120_000)
	public void computationCreationOnly() {
		int count = 100;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		long totalCreations = 0;
		long start = System.nanoTime();

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				CollectionProducer computation = buildSimilarityComputation(
						cp(tensors[i]), cp(tensors[j]));
				totalCreations++;
			}
		}

		long elapsed = System.nanoTime() - start;
		log("Computation creation only: " + totalCreations + " in " +
				String.format("%.1f", elapsed / 1_000_000_000.0) + "s (" +
				String.format("%.3f", elapsed / (double) totalCreations / 1_000_000.0) +
				" ms/creation)");
	}

	/**
	 * Measures evaluation cost when a single computation is reused with
	 * different data — demonstrating the potential speedup from caching
	 * the computation graph and only swapping input data.
	 */
	@Test(timeout = 120_000)
	public void cachedComputationEvaluation() {
		int count = 100;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		CollectionProducer cachedComputation = buildSimilarityComputation(
				cp(tensors[0]), cp(tensors[1]));

		PackedCollection warmup = cachedComputation.evaluate();

		long totalEvals = 0;
		long start = System.nanoTime();

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				PackedCollection result = cachedComputation.evaluate();
				totalEvals++;
			}
		}

		long elapsed = System.nanoTime() - start;
		log("Cached computation evaluation: " + totalEvals + " in " +
				String.format("%.1f", elapsed / 1_000_000_000.0) + "s (" +
				String.format("%.3f", elapsed / (double) totalEvals / 1_000_000.0) +
				" ms/eval)");
	}

	/**
	 * Measures the differenceSimilarity path used by PrototypeDiscovery's
	 * {@code computeMissingSimilarities()} method.
	 */
	@Test(timeout = 120_000)
	public void differenceSimilarityBaseline() {
		int count = 100;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		long totalComparisons = 0;
		long start = System.nanoTime();

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				PackedCollection a = tensors[i].range(
						new TraversalPolicy(true, FRAMES, BINS, 1)).traverse(1);
				PackedCollection b = tensors[j].range(
						new TraversalPolicy(true, FRAMES, BINS, 1)).traverse(1);

				PackedCollection diff = differenceMagnitude(BINS)
						.evaluate(a, b);
				double similarity = diff.doubleStream().sum();
				totalComparisons++;
			}
		}

		long elapsed = System.nanoTime() - start;
		log("differenceSimilarity: " + totalComparisons + " comparisons in " +
				String.format("%.1f", elapsed / 1_000_000_000.0) + "s (" +
				String.format("%.3f", elapsed / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison)");
	}

	/**
	 * Measures the optimized path using a single cached cosine similarity
	 * evaluable, matching the Phase 1 optimization in {@code WaveDetailsFactory}.
	 */
	@Test(timeout = 120_000)
	public void cachedCosineSimilarity() {
		int count = 100;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		Evaluable<PackedCollection> cosineEval = cosineSimilarityEvaluable(FRAMES, BINS);

		long totalComparisons = 0;
		long start = System.nanoTime();

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				double[] values = cosineEval.evaluate(tensors[i], tensors[j])
						.doubleStream().limit(FRAMES).toArray();

				int skip = (int) (values.length * 0.1);
				int total = values.length - skip - 2;
				DoubleStream.of(values).sorted().skip(skip).limit(total).average().orElseThrow();
				totalComparisons++;
			}
		}

		long elapsed = System.nanoTime() - start;
		log("=== Cached Cosine Similarity Evaluable ===");
		log("Comparisons: " + totalComparisons + " in " +
				String.format("%.1f", elapsed / 1_000_000_000.0) + "s (" +
				String.format("%.3f", elapsed / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison)");
	}

	/**
	 * Validates that the cached cosine similarity evaluable produces the
	 * same results as the original expression-tree-per-call approach.
	 */
	@Test(timeout = 120_000)
	public void cachedResultsMatchBaseline() {
		int count = 20;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		Evaluable<PackedCollection> cosineEval = cosineSimilarityEvaluable(FRAMES, BINS);

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				double baseline = productSimilarity(cp(tensors[i]), cp(tensors[j]), FRAMES);

				double[] values = cosineEval.evaluate(tensors[i], tensors[j])
						.doubleStream().limit(FRAMES).toArray();

				int skip = (int) (values.length * 0.1);
				int total = values.length - skip - 2;
				double cached = DoubleStream.of(values).sorted().skip(skip)
						.limit(total).average().orElseThrow();

				Assert.assertEquals("Mismatch at (" + i + ", " + j + ")",
						baseline, cached, 1e-6);
			}
		}

		log("All " + (count * (count - 1) / 2) + " comparisons match between " +
				"baseline and cached approaches");
	}

	/**
	 * Replicates the {@code WaveDetailsFactory.productSimilarity} computation:
	 * {@code multiply(a, b).sum(1).divide(multiply(length(1, a), length(1, b)))}.
	 */
	private CollectionProducer buildSimilarityComputation(
			CollectionProducer a, CollectionProducer b) {
		return multiply(a, b).sum(1)
				.divide(multiply(length(1, a), length(1, b)));
	}

	/**
	 * Replicates the {@code WaveDetailsFactory.productSimilarity} computation
	 * and extracts a scalar result with outlier trimming.
	 */
	private double productSimilarity(
			CollectionProducer a, CollectionProducer b, int limit) {
		double[] values = multiply(a, b).sum(1)
				.divide(multiply(length(1, a), length(1, b)))
				.evaluate().doubleStream().limit(limit).toArray();
		if (values.length == 0) return -1.0;

		int skip = 0;
		int total = values.length;
		if (total > 10) {
			skip = (int) (values.length * 0.1);
			total = total - skip - 2;
		}

		return java.util.stream.DoubleStream.of(values)
				.sorted().skip(skip).limit(total)
				.average().orElseThrow();
	}

	/**
	 * Creates the cached differenceMagnitude evaluable, matching
	 * {@code WaveDetails.differenceMagnitude(bins)}.
	 */
	private Evaluable<PackedCollection> differenceMagnitude(int bins) {
		return cv(new TraversalPolicy(bins, 1), 0)
				.subtract(cv(new TraversalPolicy(bins, 1), 1))
				.traverseEach()
				.magnitude()
				.get();
	}

	private static final Map<Long, Evaluable<PackedCollection>> cosineCache = new HashMap<>();

	/**
	 * Returns a cached evaluable that computes per-frame cosine similarity
	 * between two feature tensors of shape (frames, bins, 1).
	 */
	private static Evaluable<PackedCollection> cosineSimilarityEvaluable(int frames, int bins) {
		long key = ((long) frames << 32) | bins;
		TraversalPolicy shape = new TraversalPolicy(frames, bins, 1);
		return cosineCache.computeIfAbsent(key, k ->
				Ops.op(o -> o.multiply(o.cv(shape, 0), o.cv(shape, 1)).sum(1)
						.divide(o.multiply(o.length(1, o.cv(shape, 0)),
								o.length(1, o.cv(shape, 1))))).get());
	}

	private PackedCollection[] createRandomTensors(int count, int frames, int bins) {
		Random rng = new Random(42);
		PackedCollection[] tensors = new PackedCollection[count];
		for (int i = 0; i < count; i++) {
			tensors[i] = new PackedCollection(shape(frames, bins, 1)).randFill(rng);
		}
		log("Created " + count + " tensors with shape [" +
				frames + ", " + bins + ", 1]");
		return tensors;
	}
}
