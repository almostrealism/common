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

package org.almostrealism.persist.index.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.uml.Signature;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.persist.index.SimilarityMetric;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;


/**
 * Diagnostic probes for the per-pair similarity dispatch cost observed in
 * {@link org.almostrealism.persist.index.HnswIndex} before its scoring was
 * restructured around precompiled whole-store dispatches. Each probe isolates
 * one candidate explanation for that per-comparison cost:
 *
 * <ul>
 *   <li>whether structurally identical similarity graphs built over different
 *       vector buffers actually share a compiled kernel signature;</li>
 *   <li>what a per-pair graph build plus {@code evaluate()} costs, and how many
 *       kernel compilations it triggers;</li>
 *   <li>what the same comparison costs through a single pre-compiled evaluable
 *       built over {@link Input#value} pass-through arguments.</li>
 * </ul>
 */
public class SimilarityDispatchProbeTest extends TestSuiteBase {

	/** Vector dimensionality matching the failing performance test. */
	private static final int DIM = 128;

	/**
	 * Compares the reuse signatures of similarity graphs built over distinct
	 * vector pairs, over the same pair twice, and over pass-through inputs.
	 * Matching signatures across distinct pairs are the precondition for the
	 * platform's built-in kernel reuse to apply to the per-pair score path.
	 */
	@Test(timeout = 60000)
	public void signatureStabilityAcrossPairs() {
		PackedCollection a = randomVector();
		PackedCollection b = randomVector();
		PackedCollection c = randomVector();
		PackedCollection d = randomVector();

		String pairAb = Signature.of(SimilarityMetric.COSINE.similarity(cp(a), cp(b)));
		String pairAbAgain = Signature.of(SimilarityMetric.COSINE.similarity(cp(a), cp(b)));
		String pairCd = Signature.of(SimilarityMetric.COSINE.similarity(cp(c), cp(d)));
		String passThrough = Signature.of(SimilarityMetric.COSINE.similarity(
				Input.value(DIM, 0), Input.value(DIM, 1)));

		log("pairAbSignature=" + pairAb);
		log("pairAbAgainSignature=" + pairAbAgain);
		log("pairCdSignature=" + pairCd);
		log("passThroughSignature=" + passThrough);
		log("distinctPairSignaturesMatch=" + (pairAb != null && pairAb.equals(pairCd)));

		Assert.assertNotNull(passThrough);
	}

	/**
	 * Measures the current score path: a fresh producer graph and a fresh
	 * {@code evaluate()} for every pair, over a pool of distinct vectors as the
	 * graph walk produces. Reports per-call time and kernel compilation counts.
	 */
	@Test(timeout = 300000)
	public void perPairEvaluateCost() {
		PackedCollection[] pool = vectorPool(20);

		for (int i = 0; i < 10; i++) {
			scorePerPair(pool[i % pool.length], pool[(i + 1) % pool.length]);
		}

		long cpuBefore = HardwareOperator.cpuCompileCount;
		long gpuBefore = HardwareOperator.gpuCompileCount;
		long setsBefore = NativeCompiler.getTotalInstructionSets();

		int calls = 100;
		long start = System.nanoTime();
		double sink = 0.0;
		for (int i = 0; i < calls; i++) {
			sink += scorePerPair(pool[i % pool.length], pool[(i + 7) % pool.length]);
		}
		long elapsed = System.nanoTime() - start;

		log("perPairCalls=" + calls);
		log("perPairAvgUs=" + (elapsed / (calls * 1000.0)));
		log("perPairCpuCompiles=" + (HardwareOperator.cpuCompileCount - cpuBefore));
		log("perPairGpuCompiles=" + (HardwareOperator.gpuCompileCount - gpuBefore));
		log("perPairInstructionSets=" + (NativeCompiler.getTotalInstructionSets() - setsBefore));
		log("perPairSink=" + sink);
	}

	/**
	 * Measures the redesigned score path: one similarity evaluable compiled
	 * once over pass-through arguments, then invoked per pair with the vectors
	 * supplied as runtime arguments. Reports per-call time and kernel
	 * compilation counts for the steady state.
	 */
	@Test(timeout = 300000)
	public void precompiledEvaluateCost() {
		PackedCollection[] pool = vectorPool(20);

		Producer similarity = SimilarityMetric.COSINE.similarity(
				Input.value(DIM, 0), Input.value(DIM, 1));
		Evaluable<PackedCollection> evaluable = (Evaluable<PackedCollection>) similarity.get();

		for (int i = 0; i < 10; i++) {
			evaluable.evaluate(pool[i % pool.length], pool[(i + 1) % pool.length]);
		}

		long cpuBefore = HardwareOperator.cpuCompileCount;
		long gpuBefore = HardwareOperator.gpuCompileCount;
		long setsBefore = NativeCompiler.getTotalInstructionSets();

		int calls = 1000;
		long start = System.nanoTime();
		double sink = 0.0;
		for (int i = 0; i < calls; i++) {
			sink += evaluable.evaluate(pool[i % pool.length], pool[(i + 7) % pool.length])
					.toDouble(0);
		}
		long elapsed = System.nanoTime() - start;

		log("precompiledCalls=" + calls);
		log("precompiledAvgUs=" + (elapsed / (calls * 1000.0)));
		log("precompiledCpuCompiles=" + (HardwareOperator.cpuCompileCount - cpuBefore));
		log("precompiledGpuCompiles=" + (HardwareOperator.gpuCompileCount - gpuBefore));
		log("precompiledInstructionSets=" + (NativeCompiler.getTotalInstructionSets() - setsBefore));
		log("precompiledSink=" + sink);
	}

	/**
	 * Measures the precompiled evaluable when the argument instances are
	 * identical on every call, isolating the cost of rebuilding the prepared
	 * argument snapshot from the rest of the dispatch.
	 */
	@Test(timeout = 300000)
	public void precompiledSameArgsCost() {
		PackedCollection a = randomVector();
		PackedCollection b = randomVector();

		Producer similarity = SimilarityMetric.COSINE.similarity(
				Input.value(DIM, 0), Input.value(DIM, 1));
		Evaluable<PackedCollection> evaluable = (Evaluable<PackedCollection>) similarity.get();

		for (int i = 0; i < 10; i++) {
			evaluable.evaluate(a, b);
		}

		int calls = 1000;
		long start = System.nanoTime();
		double sink = 0.0;
		for (int i = 0; i < calls; i++) {
			sink += evaluable.evaluate(a, b).toDouble(0);
		}
		long elapsed = System.nanoTime() - start;

		log("sameArgsCalls=" + calls);
		log("sameArgsAvgUs=" + (elapsed / (calls * 1000.0)));
		log("sameArgsSink=" + sink);
	}

	/**
	 * Measures the precompiled evaluable writing into a fixed pre-allocated
	 * destination, removing per-call destination creation and portable-result
	 * handling from the dispatch.
	 */
	@Test(timeout = 300000)
	public void precompiledIntoCost() {
		PackedCollection[] pool = vectorPool(20);
		PackedCollection out = new PackedCollection(1);

		Producer similarity = SimilarityMetric.COSINE.similarity(
				Input.value(DIM, 0), Input.value(DIM, 1));
		Evaluable<PackedCollection> evaluable =
				((Evaluable<PackedCollection>) similarity.get()).into(out);

		for (int i = 0; i < 10; i++) {
			evaluable.evaluate(pool[i % pool.length], pool[(i + 1) % pool.length]);
		}

		int calls = 1000;
		long start = System.nanoTime();
		double sink = 0.0;
		for (int i = 0; i < calls; i++) {
			evaluable.evaluate(pool[i % pool.length], pool[(i + 7) % pool.length]);
			sink += out.toDouble(0);
		}
		long elapsed = System.nanoTime() - start;

		log("intoCalls=" + calls);
		log("intoAvgUs=" + (elapsed / (calls * 1000.0)));
		log("intoSink=" + sink);
	}

	/**
	 * Measures scoring a whole candidate frontier in one dispatch: a single
	 * precompiled {@code matmul([K, DIM], [DIM])} evaluable scoring K
	 * candidates at once, the batch shape actually available to the HNSW walk
	 * (one node's neighbour list). Reports both per-dispatch and per-score
	 * amortized cost.
	 */
	@Test(timeout = 300000)
	public void batchedFrontierCost() {
		int k = 32;
		PackedCollection candidates = (PackedCollection) rand(shape(k, DIM)).get().evaluate();
		PackedCollection query = randomVector();

		Producer batch = matmul(Input.value(shape(k, DIM), 0), Input.value(shape(DIM), 1));
		Evaluable<PackedCollection> evaluable = (Evaluable<PackedCollection>) batch.get();

		for (int i = 0; i < 10; i++) {
			evaluable.evaluate(candidates, query);
		}

		int calls = 300;
		long start = System.nanoTime();
		double sink = 0.0;
		for (int i = 0; i < calls; i++) {
			sink += evaluable.evaluate(candidates, query).toDouble(i % k);
		}
		long elapsed = System.nanoTime() - start;

		log("batchK=" + k);
		log("batchCalls=" + calls);
		log("batchAvgUsPerDispatch=" + (elapsed / (calls * 1000.0)));
		log("batchAvgUsPerScore=" + (elapsed / (calls * 1000.0 * k)));
		log("batchSink=" + sink);
	}

	/**
	 * Measures how the batched frontier dispatch scales with batch width, with
	 * a fixed staging buffer and a fixed output destination so the dispatch is
	 * the only per-call work. Wider batches than a neighbour list correspond to
	 * scoring larger candidate sets in one pass (brute-force construction).
	 */
	@Test(timeout = 300000)
	public void batchedScalingCost() {
		PackedCollection query = randomVector();
		int[] widths = { 32, 256, 2048, 16384 };

		for (int k : widths) {
			PackedCollection candidates = (PackedCollection) rand(shape(k, DIM)).get().evaluate();
			PackedCollection out = new PackedCollection(k);

			Producer batch = matmul(Input.value(shape(k, DIM), 0), Input.value(shape(DIM), 1));
			long compileStart = System.nanoTime();
			Evaluable<PackedCollection> evaluable =
					((Evaluable<PackedCollection>) batch.get()).into(out);

			for (int i = 0; i < 10; i++) {
				evaluable.evaluate(candidates, query);
			}
			log("scalingK=" + k + " scalingCompileWarmMs="
					+ ((System.nanoTime() - compileStart) / 1_000_000.0));

			int calls = 100;
			long start = System.nanoTime();
			double sink = 0.0;
			for (int i = 0; i < calls; i++) {
				evaluable.evaluate(candidates, query);
				sink += out.toDouble(i % k);
			}
			long elapsed = System.nanoTime() - start;

			log("scalingK=" + k
					+ " scalingAvgUsPerDispatch=" + (elapsed / (calls * 1000.0))
					+ " scalingAvgUsPerScore=" + (elapsed / (calls * 1000.0 * k))
					+ " scalingSink=" + sink);
		}
	}

	/**
	 * Scores one pair exactly the way {@code HnswIndex.score} currently does:
	 * a fresh graph over provider leaves, evaluated immediately.
	 *
	 * @param a first vector
	 * @param b second vector
	 * @return the similarity score
	 */
	private double scorePerPair(PackedCollection a, PackedCollection b) {
		return SimilarityMetric.COSINE.similarity(cp(a), cp(b)).evaluate().toDouble(0);
	}

	/**
	 * Builds a pool of distinct random vectors, each its own allocation, as
	 * {@code HnswIndex} nodes held them before the contiguous store.
	 *
	 * @param count number of vectors
	 * @return the pool
	 */
	private PackedCollection[] vectorPool(int count) {
		PackedCollection[] pool = new PackedCollection[count];
		for (int i = 0; i < count; i++) {
			pool[i] = randomVector();
		}
		return pool;
	}

	/**
	 * Generates a random vector through the computation graph.
	 *
	 * @return a new random vector
	 */
	private PackedCollection randomVector() {
		return (PackedCollection) rand(shape(DIM)).get().evaluate();
	}
}
