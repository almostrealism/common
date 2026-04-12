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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
				productSimilarity(
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
				computation.evaluate();
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
				buildSimilarityComputation(
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

		cachedComputation.evaluate();

		long totalEvals = 0;
		long start = System.nanoTime();

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				cachedComputation.evaluate();
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
				double magnitude = diff.doubleStream().sum();
				totalComparisons++;
				if (Double.isNaN(magnitude)) break;
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

		return DoubleStream.of(values)
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

	/**
	 * Measures the batched cosine similarity approach where one query
	 * tensor is compared against a batch of targets in a single kernel
	 * call. This is the Phase 2 optimization that reduces kernel launch
	 * overhead by processing {@code BATCH_SIZE} comparisons per launch.
	 */
	@Test(timeout = 120_000)
	public void batchedCosineSimilarity() {
		int count = 100;
		int batchSize = 50;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		int totalFrames = batchSize * FRAMES;
		Evaluable<PackedCollection> batchEval = cosineSimilarityEvaluable(totalFrames, BINS);

		long totalComparisons = 0;
		long dataStackTime = 0;
		long kernelTime = 0;
		long extractTime = 0;

		for (int i = 0; i < count; i++) {
			int remaining = count - i - 1;
			if (remaining == 0) continue;

			for (int batchStart = i + 1; batchStart < count; batchStart += batchSize) {
				int batchEnd = Math.min(batchStart + batchSize, count);
				int actualBatch = batchEnd - batchStart;

				long t0 = System.nanoTime();

				int elementsPerItem = FRAMES * BINS;
				PackedCollection stackedQuery = new PackedCollection(shape(totalFrames, BINS, 1));
				for (int b = 0; b < batchSize; b++) {
					stackedQuery.setMem(b * elementsPerItem, tensors[i], 0, elementsPerItem);
				}

				PackedCollection stackedTargets = new PackedCollection(shape(totalFrames, BINS, 1));
				for (int b = 0; b < actualBatch; b++) {
					stackedTargets.setMem(b * elementsPerItem, tensors[batchStart + b], 0, elementsPerItem);
				}

				long t1 = System.nanoTime();

				PackedCollection allResults = batchEval.evaluate(stackedQuery, stackedTargets);
				double[] allValues = allResults.doubleStream().toArray();

				long t2 = System.nanoTime();

				int outputElementsPerItem = FRAMES * BINS;
				for (int b = 0; b < actualBatch; b++) {
					double[] values = new double[FRAMES];
					System.arraycopy(allValues, b * outputElementsPerItem, values, 0, FRAMES);

					int skip = (int) (values.length * 0.1);
					int total = values.length - skip - 2;
					DoubleStream.of(values).sorted().skip(skip).limit(total).average().orElseThrow();
					totalComparisons++;
				}

				long t3 = System.nanoTime();
				dataStackTime += (t1 - t0);
				kernelTime += (t2 - t1);
				extractTime += (t3 - t2);
			}
		}

		long totalTime = dataStackTime + kernelTime + extractTime;
		log("=== Batched Cosine Similarity (batch=" + batchSize + ") ===");
		log("Comparisons: " + totalComparisons + " in " +
				String.format("%.1f", totalTime / 1_000_000_000.0) + "s (" +
				String.format("%.3f", totalTime / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison)");
		log("  Data stacking: " +
				String.format("%.3f", dataStackTime / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison (" +
				String.format("%.1f%%", 100.0 * dataStackTime / totalTime) + ")");
		log("  Kernel:        " +
				String.format("%.3f", kernelTime / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison (" +
				String.format("%.1f%%", 100.0 * kernelTime / totalTime) + ")");
		log("  Extraction:    " +
				String.format("%.3f", extractTime / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison (" +
				String.format("%.1f%%", 100.0 * extractTime / totalTime) + ")");
	}

	/**
	 * Validates that the batched cosine similarity approach produces
	 * the same results as the pairwise cached approach. This ensures
	 * that stacking tensors and computing in a single kernel does not
	 * introduce numerical differences.
	 */
	@Test(timeout = 120_000)
	public void batchedResultsMatchCached() {
		int count = 5;
		int batchSize = 3;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		Evaluable<PackedCollection> singleEval = cosineSimilarityEvaluable(FRAMES, BINS);

		// First, verify single eval output shape and size
		PackedCollection singleResult = singleEval.evaluate(tensors[0], tensors[1]);
		log("Single eval output: shape=" + singleResult.getShape() +
				" memLength=" + singleResult.getMemLength() +
				" totalSize=" + singleResult.getShape().getTotalSize());

		// Now test batched: stack 3 targets
		int totalFrames = batchSize * FRAMES;
		Evaluable<PackedCollection> batchEval = cosineSimilarityEvaluable(totalFrames, BINS);
		int elementsPerItem = FRAMES * BINS;

		PackedCollection stackedQuery = new PackedCollection(shape(totalFrames, BINS, 1));
		for (int b = 0; b < batchSize; b++) {
			stackedQuery.setMem(b * elementsPerItem, tensors[0], 0, elementsPerItem);
		}

		PackedCollection stackedTargets = new PackedCollection(shape(totalFrames, BINS, 1));
		for (int b = 0; b < batchSize; b++) {
			stackedTargets.setMem(b * elementsPerItem, tensors[1 + b], 0, elementsPerItem);
		}

		PackedCollection batchResult = batchEval.evaluate(stackedQuery, stackedTargets);
		log("Batch eval output: shape=" + batchResult.getShape() +
				" memLength=" + batchResult.getMemLength() +
				" totalSize=" + batchResult.getShape().getTotalSize());

		double[] batchAllValues = batchResult.doubleStream().toArray();
		log("Batch output stream length: " + batchAllValues.length);

		// Compare per-frame values for first target (tensors[1])
		double[] singleValues = singleResult.doubleStream().toArray();
		log("Single output stream length: " + singleValues.length);
		log("First 5 single values: " + singleValues[0] + ", " + singleValues[1] +
				", " + singleValues[2] + ", " + singleValues[3] + ", " + singleValues[4]);
		log("First 5 batch values: " + batchAllValues[0] + ", " + batchAllValues[1] +
				", " + batchAllValues[2] + ", " + batchAllValues[3] + ", " + batchAllValues[4]);

		// Check if batch values at offset FRAMES match single values for second target
		double[] singleValues2 = singleEval.evaluate(tensors[0], tensors[2]).doubleStream().toArray();
		log("Single(0,2) first 5: " + singleValues2[0] + ", " + singleValues2[1] +
				", " + singleValues2[2] + ", " + singleValues2[3] + ", " + singleValues2[4]);
		if (batchAllValues.length > FRAMES + 4) {
			log("Batch[FRAMES+0..4]: " + batchAllValues[FRAMES] + ", " + batchAllValues[FRAMES + 1] +
					", " + batchAllValues[FRAMES + 2] + ", " + batchAllValues[FRAMES + 3] +
					", " + batchAllValues[FRAMES + 4]);
		}

		// Each batch item's output occupies FRAMES*BINS elements (same layout as single eval),
		// with the per-frame cosine similarities at the first FRAMES positions of each block
		int outputElementsPerItem = FRAMES * BINS;

		// Verify each target
		int matched = 0;
		for (int b = 0; b < batchSize; b++) {
			double[] targetSingle = singleEval.evaluate(tensors[0], tensors[1 + b])
					.doubleStream().limit(FRAMES).toArray();
			int singleSkip = (int) (targetSingle.length * 0.1);
			int singleTotal = targetSingle.length - singleSkip - 2;
			double singleMean = DoubleStream.of(targetSingle).sorted()
					.skip(singleSkip).limit(singleTotal).average().orElseThrow();

			double[] targetBatch = new double[FRAMES];
			int batchOffset = b * outputElementsPerItem;
			if (batchOffset + FRAMES <= batchAllValues.length) {
				System.arraycopy(batchAllValues, batchOffset, targetBatch, 0, FRAMES);
			} else {
				log("ERROR: batch output too small for target " + b +
						", needed offset " + batchOffset + "+" + FRAMES +
						" but length=" + batchAllValues.length);
				continue;
			}

			int batchSkip = (int) (targetBatch.length * 0.1);
			int batchTotal = targetBatch.length - batchSkip - 2;
			double batchMean = DoubleStream.of(targetBatch).sorted()
					.skip(batchSkip).limit(batchTotal).average().orElseThrow();

			log("Target " + b + ": single=" + singleMean + " batch=" + batchMean);
			Assert.assertEquals("Mismatch at target " + b + " (tensors[0] vs tensors[" + (1 + b) + "])",
					singleMean, batchMean, 1e-6);
			matched++;
		}

		log("All " + matched + " comparisons match between single and batched approaches");
	}

	/**
	 * Full-scale batched comparison at 1000 tensors to measure end-to-end
	 * throughput of the Phase 2 batched approach.
	 */
	@Test(timeout = 600_000)
	@TestDepth(3)
	public void batchedSimilarityAtScale() {
		int count = 1000;
		int batchSize = 100;
		PackedCollection[] tensors = createRandomTensors(count, FRAMES, BINS);

		int totalFrames = batchSize * FRAMES;
		Evaluable<PackedCollection> batchEval = cosineSimilarityEvaluable(totalFrames, BINS);

		long totalComparisons = 0;
		long startTime = System.nanoTime();
		int elementsPerItem = FRAMES * BINS;

		for (int i = 0; i < count; i++) {
			for (int batchStart = i + 1; batchStart < count; batchStart += batchSize) {
				int batchEnd = Math.min(batchStart + batchSize, count);
				int actualBatch = batchEnd - batchStart;

				PackedCollection stackedQuery = new PackedCollection(shape(totalFrames, BINS, 1));
				for (int b = 0; b < batchSize; b++) {
					stackedQuery.setMem(b * elementsPerItem, tensors[i], 0, elementsPerItem);
				}

				PackedCollection stackedTargets = new PackedCollection(shape(totalFrames, BINS, 1));
				for (int b = 0; b < actualBatch; b++) {
					stackedTargets.setMem(b * elementsPerItem, tensors[batchStart + b], 0, elementsPerItem);
				}

				PackedCollection allResults = batchEval.evaluate(stackedQuery, stackedTargets);
				double[] allValues = allResults.doubleStream().toArray();

				int outputElementsPerItem = FRAMES * BINS;
				for (int b = 0; b < actualBatch; b++) {
					double[] values = new double[FRAMES];
					System.arraycopy(allValues, b * outputElementsPerItem, values, 0, FRAMES);

					int skip = (int) (values.length * 0.1);
					int total = values.length - skip - 2;
					DoubleStream.of(values).sorted().skip(skip).limit(total).average().orElseThrow();
					totalComparisons++;
				}
			}

			if (i % 100 == 0 && i > 0) {
				long elapsed = System.nanoTime() - startTime;
				log("After " + totalComparisons + " comparisons (" + i +
						" of " + count + " rows): " +
						String.format("%.3f", elapsed / (double) totalComparisons / 1_000_000.0) +
						" ms/comparison, " +
						String.format("%.1f", elapsed / 1_000_000_000.0) + "s total");
			}
		}

		long elapsed = System.nanoTime() - startTime;
		log("=== Batched Similarity at Scale (batch=" + batchSize + ") ===");
		log("Completed " + totalComparisons + " comparisons in " +
				String.format("%.1f", elapsed / 1_000_000_000.0) + "s (" +
				String.format("%.3f", elapsed / (double) totalComparisons / 1_000_000.0) +
				" ms/comparison)");
	}

	private static final Map<Long, Evaluable<PackedCollection>> cosineCache = new HashMap<>();

	/**
	 * Returns a cached evaluable that computes per-frame cosine similarity
	 * between two feature tensors of shape (frames, bins, 1). This same
	 * evaluable works for batched computation by passing
	 * {@code (batchSize * frames)} as the frames parameter.
	 */
	private static Evaluable<PackedCollection> cosineSimilarityEvaluable(int frames, int bins) {
		long key = ((long) frames << 32) | bins;
		TraversalPolicy shape = new TraversalPolicy(frames, bins, 1);
		return cosineCache.computeIfAbsent(key, k ->
				Ops.op(o -> o.multiply(o.cv(shape, 0), o.cv(shape, 1)).sum(1)
						.divide(o.multiply(o.length(1, o.cv(shape, 0)),
								o.length(1, o.cv(shape, 1))))).get());
	}

	/**
	 * Computes trimmed mean of cosine similarity values, matching
	 * the {@code WaveDetailsFactory.cachedProductSimilarity} approach.
	 */
	private static double trimmedMean(double[] values) {
		if (values.length == 0) return -1.0;

		int skip = 0;
		int total = values.length;
		if (total > 10) {
			skip = (int) (values.length * 0.1);
			total = total - skip - 2;
		}

		return DoubleStream.of(values).sorted().skip(skip).limit(total).average().orElseThrow();
	}

	/**
	 * Demonstrates the Phase 5 incremental similarity approach: compute
	 * fast approximate similarity using mean-pooled embeddings, then only
	 * perform exact comparisons for pairs above the approximate threshold.
	 *
	 * <p>Uses clustered test data to create realistic similarity structure
	 * where most cross-cluster pairs have low similarity and can be
	 * skipped. Target: reduce exact comparisons by 80-90%.</p>
	 */
	@Test(timeout = 120_000)
	public void incrementalSimilarity() {
		int count = 200;
		int clusters = 10;
		PackedCollection[] tensors = createClusteredTensors(count, FRAMES, BINS, clusters);

		// Phase 1: Compute mean-pooled embeddings
		long embeddingStart = System.nanoTime();
		double[][] embeddings = new double[count][BINS];
		for (int i = 0; i < count; i++) {
			double[] raw = tensors[i].doubleStream().toArray();
			for (int f = 0; f < FRAMES; f++) {
				for (int b = 0; b < BINS; b++) {
					embeddings[i][b] += raw[f * BINS + b];
				}
			}
			double invFrames = 1.0 / FRAMES;
			for (int b = 0; b < BINS; b++) {
				embeddings[i][b] *= invFrames;
			}
			double norm = 0;
			for (int b = 0; b < BINS; b++) {
				norm += embeddings[i][b] * embeddings[i][b];
			}
			norm = Math.sqrt(norm);
			if (norm > 0) {
				for (int b = 0; b < BINS; b++) {
					embeddings[i][b] /= norm;
				}
			}
		}
		long embeddingTime = System.nanoTime() - embeddingStart;

		// Phase 2: Find candidate pairs using approximate similarity
		double threshold = 0.85;
		long filterStart = System.nanoTime();
		long totalPairs = (long) count * (count - 1) / 2;
		List<int[]> candidates = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				double approxSim = 0;
				for (int b = 0; b < BINS; b++) {
					approxSim += embeddings[i][b] * embeddings[j][b];
				}
				if (approxSim >= threshold) {
					candidates.add(new int[]{i, j});
				}
			}
		}
		long filterTime = System.nanoTime() - filterStart;

		// Phase 3: Compute exact similarity only for candidate pairs
		Evaluable<PackedCollection> cosineEval = cosineSimilarityEvaluable(FRAMES, BINS);
		long exactStart = System.nanoTime();
		int exactComparisons = 0;
		for (int[] pair : candidates) {
			double[] values = cosineEval.evaluate(tensors[pair[0]], tensors[pair[1]])
					.doubleStream().limit(FRAMES).toArray();
			trimmedMean(values);
			exactComparisons++;
		}
		long exactTime = System.nanoTime() - exactStart;

		double reductionPercent = 100.0 * (totalPairs - candidates.size()) / totalPairs;
		long totalTime = embeddingTime + filterTime + exactTime;

		log("=== Incremental Similarity (Phase 5) ===");
		log("Clusters: " + clusters + ", Samples: " + count);
		log("Total pairs: " + totalPairs + ", Candidate pairs: " + candidates.size());
		log("Exact comparisons reduced by: " + String.format("%.1f%%", reductionPercent));
		log("Embedding time:  " + String.format("%.1f", embeddingTime / 1_000_000.0) + " ms");
		log("Filter time:     " + String.format("%.1f", filterTime / 1_000_000.0) + " ms");
		log("Exact comp time: " + String.format("%.1f", exactTime / 1_000_000.0) + " ms");
		log("Total time:      " + String.format("%.1f", totalTime / 1_000_000.0) + " ms");
		if (exactComparisons > 0) {
			log("Per exact comparison: " + String.format("%.3f",
					exactTime / (double) exactComparisons / 1_000_000.0) + " ms");
		}

		// Compare with full exact computation time estimate
		if (exactComparisons > 0) {
			double perExact = exactTime / (double) exactComparisons;
			double fullEstimate = perExact * totalPairs;
			double actualTotal = totalTime;
			log("Estimated full exact time: " + String.format("%.1f", fullEstimate / 1_000_000.0) + " ms");
			log("Incremental total time:    " + String.format("%.1f", actualTotal / 1_000_000.0) + " ms");
			log("Overall speedup: " + String.format("%.2fx", fullEstimate / actualTotal));
		}

		Assert.assertTrue("Expected at least 50% reduction but got " +
				String.format("%.1f%%", reductionPercent),
				reductionPercent >= 50.0);
	}

	/**
	 * Validates that the approximate similarity (mean-pooled embedding cosine)
	 * is positively correlated with exact per-frame cosine similarity. Pairs
	 * with high approximate similarity should generally have high exact
	 * similarity, confirming the filtering approach preserves important pairs.
	 */
	@Test(timeout = 120_000)
	public void approximateSimilarityCorrelation() {
		int count = 50;
		int clusters = 5;
		PackedCollection[] tensors = createClusteredTensors(count, FRAMES, BINS, clusters);

		// Compute mean-pooled embeddings
		double[][] embeddings = new double[count][BINS];
		for (int i = 0; i < count; i++) {
			double[] raw = tensors[i].doubleStream().toArray();
			for (int f = 0; f < FRAMES; f++) {
				for (int b = 0; b < BINS; b++) {
					embeddings[i][b] += raw[f * BINS + b];
				}
			}
			double invFrames = 1.0 / FRAMES;
			for (int b = 0; b < BINS; b++) {
				embeddings[i][b] *= invFrames;
			}
			double norm = 0;
			for (int b = 0; b < BINS; b++) {
				norm += embeddings[i][b] * embeddings[i][b];
			}
			norm = Math.sqrt(norm);
			if (norm > 0) {
				for (int b = 0; b < BINS; b++) {
					embeddings[i][b] /= norm;
				}
			}
		}

		// Compute both approximate and exact similarities for sample pairs
		cosineSimilarityEvaluable(FRAMES, BINS);
		int sameClusterCount = 0;
		int sameClusterHighApprox = 0;

		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				double approxSim = 0;
				for (int b = 0; b < BINS; b++) {
					approxSim += embeddings[i][b] * embeddings[j][b];
				}

				boolean sameCluster = (i % clusters) == (j % clusters);
				if (sameCluster) {
					sameClusterCount++;
					if (approxSim >= 0.85) {
						sameClusterHighApprox++;
					}
				}
			}
		}

		double recall = sameClusterCount > 0
				? 100.0 * sameClusterHighApprox / sameClusterCount : 0;
		log("Same-cluster recall at threshold 0.85: " +
				String.format("%.1f%%", recall) +
				" (" + sameClusterHighApprox + "/" + sameClusterCount + ")");

		Assert.assertTrue("Expected at least 70% recall for same-cluster pairs but got " +
				String.format("%.1f%%", recall),
				recall >= 70.0);
	}

	/**
	 * Creates tensors with cluster structure for testing approximate filtering.
	 * Each cluster has a random Gaussian center, and tensors within a cluster
	 * are the center plus noise. This creates realistic similarity structure
	 * where within-cluster pairs have high similarity and cross-cluster pairs
	 * have low similarity.
	 */
	private PackedCollection[] createClusteredTensors(
			int count, int frames, int bins, int clusters) {
		Random rng = new Random(42);
		PackedCollection[] tensors = new PackedCollection[count];

		double[][] centers = new double[clusters][bins];
		for (int c = 0; c < clusters; c++) {
			for (int b = 0; b < bins; b++) {
				centers[c][b] = rng.nextGaussian();
			}
		}

		for (int i = 0; i < count; i++) {
			int cluster = i % clusters;
			tensors[i] = new PackedCollection(shape(frames, bins, 1));
			double[] data = new double[frames * bins];
			for (int f = 0; f < frames; f++) {
				for (int b = 0; b < bins; b++) {
					data[f * bins + b] = centers[cluster][b] + rng.nextGaussian() * 0.3;
				}
			}
			tensors[i].setMem(0, data, 0, data.length);
		}

		log("Created " + count + " clustered tensors (" + clusters +
				" clusters) with shape [" + frames + ", " + bins + ", 1]");
		return tensors;
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
