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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.model.BranchBlock;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.Random;
import java.util.function.IntConsumer;

/**
 * Pins the behaviour of the autoregressive KV-cached attention block built by
 * {@link AttentionFeatures#attention} from the {@code attention.pdsl} asset against two
 * references that do not depend on the block's implementation:
 * <ul>
 *   <li>{@link HostReference}, the same arithmetic written out in plain double precision
 *       on the host, including its own key/value cache, so every stage (norm, projection,
 *       per-head QK-norm, rotary embedding, GQA expansion, cache write, scores, scale, mask,
 *       softmax, weighted values, output projection) is checked independently of the
 *       framework's attention layers; and</li>
 *   <li>golden outputs captured from the Java assembly that preceded the asset
 *       ({@code AttentionFeatures.attentionImpl} at commit {@code 24e24a136}) with the
 *       seeds and dimensions used here, for the configurations that assembly computed
 *       correctly.</li>
 * </ul>
 *
 * <p>Every test runs several consecutive positions through one compiled block so the
 * caches that persist across forward passes are exercised, and uses more than one head
 * and (where relevant) fewer KV heads than query heads so per-head and grouped-query
 * stages are not trivially satisfied. Position 0 alone proves little — with a single
 * unmasked key the output is the value projection of the token — so the positions after
 * it are where the query, key, cache and score stages are actually checked.</p>
 *
 * <p>The four-head configurations use as many heads as the cache has rows. The Java
 * assembly computed wrong scores in exactly that case (see
 * {@link #javaAssemblyComputedWrongScoresWhenHeadsEqualledSequenceLength}), which is why its
 * golden values are only used for the two-head configurations.</p>
 */
public class AttentionAssetTest extends TestSuiteBase implements AttentionFeatures {

	/** Absolute tolerance between the single-precision block and the double-precision references. */
	private static final double TOLERANCE = 1e-4;

	/** Sequence length (cache rows) shared by every configuration. */
	private static final int SEQ_LEN = 4;

	/** Number of consecutive forward passes each test runs through one compiled block. */
	private static final int STEPS = 3;

	/** Epsilon of the pre-attention RMSNorm. */
	private static final double EPSILON = 1e-5;

	/** RoPE base frequency used by the standard configurations. */
	private static final double THETA = 10000.0;

	/**
	 * Output of the Java assembly on master for the standard configuration
	 * ({@code heads=2, kvHeads=2, headSize=4}, seed 7), one row per position.
	 */
	private static final double[][] STANDARD_GOLDEN = {
			{ -0.6179237, 0.7088677, 0.3376946, -0.0860314, 1.2971648, -0.9490829, 0.3997871, 0.3592657 },
			{ -0.1045305, 0.4201971, -0.0472333, -0.0779168, 1.0320345, -0.5038040, -0.0416454, 0.1864730 },
			{ -0.0791337, 0.0872090, 0.5513603, -0.0908490, -0.3080583, 0.1744099, 0.0102047, 0.2414578 } };

	/**
	 * Output of the Java assembly on master for two query heads served by one KV head
	 * ({@code heads=2, kvHeads=1, headSize=4}, seed 29), one row per position.
	 */
	private static final double[][] GROUPED_TWO_TO_ONE_GOLDEN = {
			{ 0.6150956, -0.6257946, -0.2642206, -0.7598597, -0.2194256, 1.0021650, -0.4734686, 0.8385313 },
			{ -0.0926120, -0.3574356, 0.0917762, 0.2396217, 0.5725985, 0.1818562, -0.2665381, -0.4071121 },
			{ -0.0951423, -0.4710161, -0.1649519, 0.0466254, 0.0842201, -0.0275496, -0.1133358, -0.7997360 } };

	/**
	 * Output of the Java assembly on master at position 1 for four heads without grouping
	 * ({@code heads=4, kvHeads=4, headSize=4}, seed 21): the head count equals the cache
	 * length, and the scores it computed there were wrong.
	 */
	private static final double[] FOUR_HEADS_POSITION_1_ON_MASTER = {
			0.1504269, -0.2572910, 0.9978460, 0.6666784, -0.2972435, 0.1103109, -0.0397766, 0.7950818,
			-0.6133082, 0.1648636, -0.0259089, 0.3705407, 1.0581915, -1.2941585, -0.9647709, -0.0362560 };

	/**
	 * Standard multi-head attention: two heads of four, no bias, no QK-norm. Every stage
	 * of the asset except GQA expansion and the optional stages is exercised.
	 */
	@Test(timeout = 300000)
	public void standardAttentionMatchesHostReference() {
		Weights w = new Weights(2, 2, 4, false, false, 7L);
		assertMatchesReference(w, false, runStandard(w), "standard");
	}

	/** The asset reproduces the Java assembly's output for the standard configuration. */
	@Test(timeout = 300000)
	public void standardAttentionMatchesMasterGoldenValues() {
		Weights w = new Weights(2, 2, 4, false, false, 7L);
		assertMatchesGolden(STANDARD_GOLDEN, runStandard(w), "standard");
	}

	/**
	 * Two query heads served by one KV head: the key and value rows written to the cache
	 * are each KV head's row duplicated for every query head it serves.
	 */
	@Test(timeout = 300000)
	public void groupedQueryTwoToOneMatchesHostReference() {
		Weights w = new Weights(2, 1, 4, false, false, 29L);
		assertMatchesReference(w, false, runStandard(w), "gqa-2-1");
	}

	/** The asset reproduces the Java assembly's output for the two-to-one grouped configuration. */
	@Test(timeout = 300000)
	public void groupedQueryTwoToOneMatchesMasterGoldenValues() {
		Weights w = new Weights(2, 1, 4, false, false, 29L);
		assertMatchesGolden(GROUPED_TWO_TO_ONE_GOLDEN, runStandard(w), "gqa-2-1");
	}

	/**
	 * Four heads without grouping, as many heads as the cache has rows: the scores stage
	 * must tile the query across the cache rows even though the query's shape is a prefix
	 * of the cache's.
	 */
	@Test(timeout = 300000)
	public void fourHeadsMatchHostReference() {
		Weights w = new Weights(4, 4, 4, false, false, 21L);
		assertMatchesReference(w, false, runStandard(w), "mha4");
	}

	/**
	 * Documents the defect the migration removed. On master the Java assembly relied on the
	 * implicit broadcast of {@code multiply(cache, query)} for the scores, which matches
	 * leading dimensions: with as many heads as cache rows the {@code (heads, headSize)} query
	 * is a prefix of the {@code (seqLen, heads, headSize)} cache and each query element was
	 * spread over a run of cache elements instead of the query being tiled across rows. Its
	 * output at position 1 therefore disagrees with the host reference, while the asset's
	 * output ({@link #fourHeadsMatchHostReference}) agrees. Position 0 is unaffected because
	 * a single unmasked key makes the scores irrelevant.
	 */
	@Test(timeout = 300000)
	public void javaAssemblyComputedWrongScoresWhenHeadsEqualledSequenceLength() {
		Weights w = new Weights(4, 4, 4, false, false, 21L);
		HostReference reference = new HostReference(w, false);
		reference.forward(w.input(0), 0);
		double[] expected = reference.forward(w.input(1), 1);

		double largest = 0;
		for (int i = 0; i < expected.length; i++) {
			largest = Math.max(largest, Math.abs(expected[i] - FOUR_HEADS_POSITION_1_ON_MASTER[i]));
		}
		Assert.assertTrue("master's position 1 output differed from the reference by at most "
				+ largest, largest > 0.1);
	}

	/**
	 * A non-empty {@link ComputeRequirement} is applied to every layer the asset builds
	 * rather than rejected: {@code attention(...)} used to throw {@link IllegalArgumentException}
	 * for any caller that supplied one (as every {@code transformer(...)} overload does when
	 * its own caller supplies requirements), even though nothing about the requirement is
	 * incompatible with an asset-built block. Forcing {@link ComputeRequirement#CPU} must not
	 * throw and must not change the result.
	 */
	@Test(timeout = 300000)
	public void computeRequirementsAreAppliedNotRejected() {
		Weights w = new Weights(2, 2, 4, false, false, 7L);
		PackedCollection position = new PackedCollection(shape(1));
		Block block = attention(w.heads, w.kvHeads, w.rms, w.wk, w.wv, w.wq, w.wo,
				w.bk, w.bv, w.bq, w.qkNormQ, w.qkNormK, cp(w.freqCis), p(position), EPSILON,
				ComputeRequirement.CPU);
		assertMatchesGolden(STANDARD_GOLDEN, run(w, block, position, step -> { }), "standard with CPU requirement");
	}

	/**
	 * {@link BranchBlock#setComputeRequirements} propagates the requirement to every child
	 * appended to the branch, matching {@code attention.pdsl}'s {@code branch keys}/
	 * {@code branch values} bodies: those compile to exactly this shape (a
	 * {@link SequentialBlock} that calls {@link SequentialBlock#branch(Block)} and appends a
	 * dense layer to the returned branch), and the requirement set on the outer sequence must
	 * reach the dense layer inside the branch rather than stopping at the default (no-op)
	 * {@link Block#setComputeRequirements}.
	 */
	@Test(timeout = 60000)
	public void branchBlockPropagatesComputeRequirementsToChildren() {
		TraversalPolicy inputShape = shape(1, 4);
		PackedCollection weights = new PackedCollection(shape(4, 4));

		SequentialBlock root = new SequentialBlock(inputShape);
		SequentialBlock keys = root.branch(new SequentialBlock(inputShape));
		keys.add(dense(weights));
		DefaultCellularLayer denseLayer = (DefaultCellularLayer) keys.lastBlock();

		root.setComputeRequirements(ComputeRequirement.CPU);

		Assert.assertTrue("expected the branch's dense layer to carry the CPU requirement",
				denseLayer.getComputeRequirements().contains(ComputeRequirement.CPU));
	}

	/**
	 * {@code attentionArguments(...)} rejects a non-positive head count with
	 * {@link IllegalArgumentException} before the model-dimension check divides by it: with
	 * {@code heads <= 0} the previous validation order reached {@code dim % heads} first and
	 * threw an uncaught {@link ArithmeticException} for {@code heads == 0} instead of the
	 * documented rejection.
	 */
	@Test(timeout = 60000)
	public void attentionArgumentsRejectsNonPositiveHeadCount() {
		Weights w = new Weights(2, 2, 4, false, false, 47L);
		PackedCollection position = new PackedCollection(shape(1));

		try {
			attentionArguments(0, w.kvHeads, w.rms, w.wk, w.wv, w.wq, w.wo,
					SEQ_LEN, p(position), EPSILON);
			Assert.fail("attentionArguments() should reject a zero head count");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		try {
			attentionArguments(-1, w.kvHeads, w.rms, w.wk, w.wv, w.wq, w.wo,
					SEQ_LEN, p(position), EPSILON);
			Assert.fail("attentionArguments() should reject a negative head count");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * {@code attentionArguments(...)} zero-initializes the key and value caches it allocates:
	 * {@link PackedCollection} allocates backing memory directly from the hardware provider
	 * without guaranteeing zeroed contents, and the deleted Java assembly this asset replaced
	 * relied on caches starting at zero so unwritten rows do not perturb the softmax/scale
	 * numerics of the first few forward passes.
	 */
	@Test(timeout = 60000)
	public void attentionArgumentsClearsCaches() {
		Weights w = new Weights(2, 2, 4, false, false, 47L);
		PackedCollection position = new PackedCollection(shape(1));

		Map<String, Object> args = attentionArguments(w.heads, w.kvHeads, w.rms,
				w.wk, w.wv, w.wq, w.wo, SEQ_LEN, p(position), EPSILON);

		assertAllZero("key_cache", (PackedCollection) args.get("key_cache"));
		assertAllZero("value_cache", (PackedCollection) args.get("value_cache"));
	}

	/** Asserts every element of {@code cache} is zero. */
	private static void assertAllZero(String label, PackedCollection cache) {
		double[] values = cache.toArray();
		for (int i = 0; i < values.length; i++) {
			Assert.assertEquals(label + " element " + i, 0.0, values[i], 0.0);
		}
	}

	/**
	 * {@code attention(...)} requires both QK-Norm weights or neither: a caller that supplies
	 * only one (for example, a partially wired model builder) gets a clear rejection instead
	 * of a normalization silently applied to just the query or just the key.
	 */
	@Test(timeout = 60000)
	public void qkNormRequiresBothWeights() {
		Weights w = new Weights(2, 2, 4, false, true, 41L);
		PackedCollection position = new PackedCollection(shape(1));

		try {
			attention(w.heads, w.kvHeads, w.rms, w.wk, w.wv, w.wq, w.wo,
					w.bk, w.bv, w.bq, w.qkNormQ, null, cp(w.freqCis), p(position), EPSILON);
			Assert.fail("attention() should reject a query QK-Norm weight without a matching key weight");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		try {
			attention(w.heads, w.kvHeads, w.rms, w.wk, w.wv, w.wq, w.wo,
					w.bk, w.bv, w.bq, null, w.qkNormK, cp(w.freqCis), p(position), EPSILON);
			Assert.fail("attention() should reject a key QK-Norm weight without a matching query weight");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * {@code attention(...)} rejects a {@code rmsAttWeight} whose length is not a multiple of
	 * the head count: the asset divides the model dimension evenly across heads, so such a
	 * configuration cannot be built.
	 */
	@Test(timeout = 60000)
	public void attentionRejectsModelDimensionNotDivisibleByHeads() {
		Weights w = new Weights(2, 2, 4, false, false, 43L);
		PackedCollection badRms = new PackedCollection(shape(w.dim + 1));
		PackedCollection position = new PackedCollection(shape(1));

		try {
			attention(w.heads, w.kvHeads, badRms, w.wk, w.wv, w.wq, w.wo,
					w.bk, w.bv, w.bq, w.qkNormQ, w.qkNormK, cp(w.freqCis), p(position), EPSILON);
			Assert.fail("attention() should reject a model dimension not divisible by the head count");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * {@code attentionArguments(...)} rejects a KV head count that does not evenly divide
	 * the query head count, and a non-positive KV head count, before the asset's PDSL layer
	 * ever computes {@code heads / kv_heads} for the GQA {@code repeat_each} stage: a silent
	 * integer-division truncation there would build a cache-mismatched block instead of
	 * failing at construction time, and a zero KV head count would reach the division as an
	 * uncaught {@link ArithmeticException} instead of the documented rejection.
	 */
	@Test(timeout = 60000)
	public void attentionArgumentsRejectsInvalidGqaHeadRatio() {
		Weights w = new Weights(3, 2, 4, false, false, 47L);
		PackedCollection position = new PackedCollection(shape(1));

		try {
			attentionArguments(w.heads, w.kvHeads, w.rms, w.wk, w.wv, w.wq, w.wo,
					SEQ_LEN, p(position), EPSILON);
			Assert.fail("attentionArguments() should reject a query head count not divisible by the KV head count");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		try {
			attentionArguments(w.heads, 0, w.rms, w.wk, w.wv, w.wq, w.wo,
					SEQ_LEN, p(position), EPSILON);
			Assert.fail("attentionArguments() should reject a non-positive KV head count");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * Grouped-query attention with projection biases: four query heads served by two KV
	 * heads, so the cache write depends on the per-head duplication of keys and values.
	 */
	@Test(timeout = 300000)
	public void groupedQueryAttentionWithBiasMatchesHostReference() {
		Weights w = new Weights(4, 2, 4, true, false, 11L);
		assertMatchesReference(w, false, runStandard(w), "gqa+bias");
	}

	/**
	 * QK-norm normalizes each head's query and key vector by that head's own root mean
	 * square (the {@code RMSNorm(head_dim)} of Qwen3), not by a statistic shared across
	 * heads. Heads with very different magnitudes make the two readings diverge, so the
	 * reference below, which normalizes per head, only agrees with a per-head block.
	 */
	@Test(timeout = 300000)
	public void qkNormNormalizesEachHeadSeparately() {
		Weights w = new Weights(4, 2, 4, false, true, 13L);
		assertMatchesReference(w, false, runStandard(w), "gqa+qknorm");
	}

	/**
	 * Multidimensional relative attention: two head groups rotated by different frequency
	 * tables at different positions, on top of grouped-query expansion.
	 */
	@Test(timeout = 300000)
	public void headGroupAttentionMatchesHostReference() {
		Weights w = new Weights(4, 2, 4, false, false, 17L);
		assertMatchesReference(w, true, runHeadGroups(w), "mra+gqa");
	}

	/**
	 * Compares every output vector produced by the block described by {@code w} with
	 * {@link HostReference} run over the same inputs and positions.
	 */
	private void assertMatchesReference(Weights w, boolean headGroups, double[][] actual, String label) {
		HostReference reference = new HostReference(w, headGroups);
		for (int step = 0; step < STEPS; step++) {
			double[] expected = reference.forward(w.input(step), step);
			assertClose(label + " position " + step, expected, actual[step]);
		}
	}

	/** Compares every output vector with the values recorded from the Java assembly on master. */
	private static void assertMatchesGolden(double[][] golden, double[][] actual, String label) {
		for (int step = 0; step < STEPS; step++) {
			assertClose(label + " golden position " + step, golden[step], actual[step]);
		}
	}

	/**
	 * Builds the standard-RoPE block for {@code w}, compiles it and runs {@code STEPS}
	 * positions through it, returning one output vector per position.
	 */
	private double[][] runStandard(Weights w) {
		PackedCollection position = new PackedCollection(shape(1));
		Block block = attention(w.heads, w.kvHeads, w.rms, w.wk, w.wv, w.wq, w.wo,
				w.bk, w.bv, w.bq, w.qkNormQ, w.qkNormK, cp(w.freqCis), p(position), EPSILON);
		return run(w, block, position, step -> { });
	}

	/**
	 * Builds the per-head-group (MRA) block for {@code w}, whose two groups read their
	 * rotary positions from separate device-resident counters that advance at different
	 * rates from the cache position, and runs {@code STEPS} positions through it.
	 */
	private double[][] runHeadGroups(Weights w) {
		PackedCollection position = new PackedCollection(shape(1));
		PackedCollection[] groupPositions = { new PackedCollection(shape(1)), new PackedCollection(shape(1)) };
		Producer<PackedCollection>[] groupProducers = new Producer[2];
		groupProducers[0] = p(groupPositions[0]);
		groupProducers[1] = p(groupPositions[1]);
		HeadGroupConfig[] groups = HeadGroupConfig.fromParams(w.groupThetas, w.headSize, SEQ_LEN,
				w.headsPerGroup, groupProducers);

		Block block = attention(w.heads, w.kvHeads, w.rms, w.wk, w.wv, w.wq, w.wo,
				groups, p(position), EPSILON);
		return run(w, block, position, step -> {
			for (int g = 0; g < groupPositions.length; g++) {
				double groupPosition = w.groupPosition(g, step);
				groupPositions[g].fill(groupPosition);
			}
		});
	}

	/**
	 * Compiles {@code block} inside a {@link Model} and runs it once per position,
	 * advancing {@code position} before each pass and invoking {@code prepare} so a
	 * configuration can update any further per-step state.
	 */
	private double[][] run(Weights w, Block block, PackedCollection position, IntConsumer prepare) {
		Model model = new Model(shape(1, w.dim));
		model.add(block);
		CompiledModel compiled = model.compile();

		double[][] outputs = new double[STEPS][];
		for (int step = 0; step < STEPS; step++) {
			position.fill(step);
			prepare.accept(step);
			outputs[step] = compiled.forward(w.input(step)).toArray();
			log(label(w) + " position " + step + " -> " + literal(outputs[step]));
		}
		return outputs;
	}

	/** Compares two vectors element-wise within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], actual[i], TOLERANCE);
		}
	}

	/** Formats a vector as a Java array literal, the form the golden values are recorded in. */
	private static String literal(double[] values) {
		StringBuilder sb = new StringBuilder("{ ");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(String.format("%.7f", values[i]));
		}
		return sb.append(" }").toString();
	}

	/** A short description of a configuration for log lines. */
	private static String label(Weights w) {
		return "attention heads=" + w.heads + " kvHeads=" + w.kvHeads + " headSize=" + w.headSize
				+ (w.bq != null ? " bias" : "") + (w.qkNormQ != null ? " qknorm" : "");
	}


	/**
	 * Deterministic weights, inputs and rotary tables for one attention configuration,
	 * generated from a seed so the same tensors reach the block and the host reference.
	 */
	private final class Weights {
		/** Number of query heads. */
		final int heads;
		/** Number of key/value heads (equal to {@link #heads} without grouped-query attention). */
		final int kvHeads;
		/** Elements per head. */
		final int headSize;
		/** Model dimension, {@code heads * headSize}. */
		final int dim;
		/** Key/value projection width, {@code kvHeads * headSize}. */
		final int kvDim;
		/** Pre-attention RMSNorm scale, shape {@code [dim]}. */
		final PackedCollection rms;
		/** Query projection, shape {@code [dim, dim]}. */
		final PackedCollection wq;
		/** Key projection, shape {@code [kvDim, dim]}. */
		final PackedCollection wk;
		/** Value projection, shape {@code [kvDim, dim]}. */
		final PackedCollection wv;
		/** Output projection, shape {@code [dim, dim]}. */
		final PackedCollection wo;
		/** Query projection bias, or {@code null} when the configuration has no biases. */
		final PackedCollection bq;
		/** Key projection bias, or {@code null} when the configuration has no biases. */
		final PackedCollection bk;
		/** Value projection bias, or {@code null} when the configuration has no biases. */
		final PackedCollection bv;
		/** Per-head query QK-norm scale, shape {@code [heads, headSize]}, or {@code null}. */
		final PackedCollection qkNormQ;
		/** Per-head key QK-norm scale, shape {@code [kvHeads, headSize]}, or {@code null}. */
		final PackedCollection qkNormK;
		/** Rotary table for the standard configurations, shape {@code [SEQ_LEN, headSize / 2, 2]}. */
		final PackedCollection freqCis;
		/** RoPE base frequency of each head group in the MRA configuration. */
		final double[] groupThetas = { THETA, 500.0 };
		/** Query heads in each head group in the MRA configuration. */
		final int[] headsPerGroup;
		/** Rotary table of each head group in the MRA configuration. */
		final PackedCollection[] groupFreqCis;
		/** Seeded source of every random tensor. */
		private final Random random;

		/**
		 * Generates the tensors for one configuration.
		 *
		 * @param heads    number of query heads
		 * @param kvHeads  number of key/value heads
		 * @param headSize elements per head
		 * @param bias     whether the projections carry biases
		 * @param qkNorm   whether QK-norm scales are present
		 * @param seed     seed of the random tensors
		 */
		Weights(int heads, int kvHeads, int headSize, boolean bias, boolean qkNorm, long seed) {
			this.heads = heads;
			this.kvHeads = kvHeads;
			this.headSize = headSize;
			this.dim = heads * headSize;
			this.kvDim = kvHeads * headSize;
			this.random = new Random(seed);
			this.rms = uniform(0.5, 1.5, dim);
			this.wq = uniform(-0.5, 0.5, dim, dim);
			this.wk = uniform(-0.5, 0.5, kvDim, dim);
			this.wv = uniform(-0.5, 0.5, kvDim, dim);
			this.wo = uniform(-0.5, 0.5, dim, dim);
			this.bq = bias ? uniform(-0.3, 0.3, dim) : null;
			this.bk = bias ? uniform(-0.3, 0.3, kvDim) : null;
			this.bv = bias ? uniform(-0.3, 0.3, kvDim) : null;
			this.qkNormQ = qkNorm ? uniform(0.5, 1.5, heads, headSize) : null;
			this.qkNormK = qkNorm ? uniform(0.5, 1.5, kvHeads, headSize) : null;
			this.freqCis = RotationFeatures.computeRopeFreqs(THETA, headSize, SEQ_LEN).evaluate();
			this.headsPerGroup = new int[] { heads / 2, heads - heads / 2 };
			this.groupFreqCis = new PackedCollection[] {
					RotationFeatures.computeRopeFreqs(groupThetas[0], headSize, SEQ_LEN).evaluate(),
					RotationFeatures.computeRopeFreqs(groupThetas[1], headSize, SEQ_LEN).evaluate() };
		}

		/**
		 * The token vector for a step, produced on the device: element {@code i} is
		 * {@code sin(1 + 0.7 i + 2.1 step)}, with head 0 carrying values an order of
		 * magnitude larger than the others so per-head and cross-head statistics differ.
		 *
		 * @param step the forward pass index
		 * @return the {@code [1, dim]} token vector
		 */
		PackedCollection input(int step) {
			CollectionProducer index = integers(0, dim);
			CollectionProducer scale = index.lessThan(c((double) headSize)).multiply(9.0).add(1.0);
			CollectionProducer values = sin(index.multiply(0.7).add(1.0 + 2.1 * step)).multiply(scale);
			return values.reshape(shape(1, dim)).evaluate();
		}

		/**
		 * The rotary position head group {@code g} uses at {@code step} in the MRA
		 * configuration: group 0 lags the cache position, group 1 leads it.
		 *
		 * @param g    the head group
		 * @param step the forward pass index
		 * @return the group's rotary position
		 */
		int groupPosition(int g, int step) {
			return g == 0 ? step / 2 : Math.min(SEQ_LEN - 1, step + 1);
		}

		/**
		 * Draws a tensor of the given shape from the seeded source, uniformly in
		 * {@code [min, max)}, produced on the device.
		 */
		private PackedCollection uniform(double min, double max, int... dims) {
			TraversalPolicy shape = shape(dims);
			return rand(shape, random).multiply(max - min).add(min).reshape(shape).evaluate();
		}
	}

	/**
	 * The attention arithmetic in plain double precision with its own KV cache: an oracle
	 * for what a single-token forward pass must produce at each position.
	 */
	private static final class HostReference {
		/** The configuration being reproduced. */
		private final Weights w;
		/** Whether rotation uses the per-head-group tables and positions (MRA). */
		private final boolean headGroups;
		/** Rotated keys of every position seen so far, one {@code dim} row per position. */
		private final double[][] keyCache;
		/** Values of every position seen so far, one {@code dim} row per position. */
		private final double[][] valueCache;

		/**
		 * Creates an oracle for {@code w} with empty caches.
		 *
		 * @param w          the configuration
		 * @param headGroups whether the MRA rotation applies
		 */
		HostReference(Weights w, boolean headGroups) {
			this.w = w;
			this.headGroups = headGroups;
			this.keyCache = new double[SEQ_LEN][w.dim];
			this.valueCache = new double[SEQ_LEN][w.dim];
		}

		/**
		 * One forward pass: normalizes, projects, applies QK-norm per head where configured,
		 * rotates, writes the caches at {@code position}, then computes masked per-head
		 * softmax attention over the cache and the output projection.
		 *
		 * @param token    the token vector
		 * @param position the sequence position
		 * @return the attention output for the token
		 */
		double[] forward(PackedCollection token, int position) {
			double[] normed = rmsnorm(token.toArray(), w.rms.toArray(), EPSILON, w.dim);
			double[] q = project(w.wq, w.bq, normed, w.dim);
			double[] k = project(w.wk, w.bk, normed, w.kvDim);
			double[] v = project(w.wv, w.bv, normed, w.kvDim);

			if (w.qkNormQ != null) {
				q = rmsnorm(q, w.qkNormQ.toArray(), 1e-6, w.headSize);
				k = rmsnorm(k, w.qkNormK.toArray(), 1e-6, w.headSize);
			}

			int perGroup = w.heads / w.kvHeads;
			for (int h = 0; h < w.heads; h++) {
				rotate(q, h, ropeTable(h), ropePosition(h, position));
			}
			for (int j = 0; j < w.kvHeads; j++) {
				rotate(k, j, ropeTable(j * perGroup), ropePosition(j * perGroup, position));
			}

			for (int h = 0; h < w.heads; h++) {
				for (int i = 0; i < w.headSize; i++) {
					keyCache[position][h * w.headSize + i] = k[(h / perGroup) * w.headSize + i];
					valueCache[position][h * w.headSize + i] = v[(h / perGroup) * w.headSize + i];
				}
			}

			double[] context = new double[w.dim];
			for (int h = 0; h < w.heads; h++) {
				double[] scores = new double[SEQ_LEN];
				double max = Double.NEGATIVE_INFINITY;
				for (int s = 0; s < SEQ_LEN; s++) {
					double dot = 0;
					for (int i = 0; i < w.headSize; i++) {
						dot += q[h * w.headSize + i] * keyCache[s][h * w.headSize + i];
					}
					scores[s] = dot / Math.sqrt(w.headSize) + (s > position ? -10000.0 : 0.0);
					max = Math.max(max, scores[s]);
				}
				double sum = 0;
				for (int s = 0; s < SEQ_LEN; s++) {
					scores[s] = Math.exp(scores[s] - max);
					sum += scores[s];
				}
				for (int s = 0; s < SEQ_LEN; s++) {
					for (int i = 0; i < w.headSize; i++) {
						context[h * w.headSize + i] += scores[s] / sum * valueCache[s][h * w.headSize + i];
					}
				}
			}
			return project(w.wo, null, context, w.dim);
		}

		/** Rotary table for query head {@code h}: the shared table, or its group's table under MRA. */
		private double[] ropeTable(int h) {
			return headGroups ? w.groupFreqCis[group(h)].toArray() : w.freqCis.toArray();
		}

		/** Rotary position for query head {@code h} at the cache position. */
		private int ropePosition(int h, int position) {
			return headGroups ? w.groupPosition(group(h), position) : position;
		}

		/** The head group query head {@code h} belongs to. */
		private int group(int h) {
			return h < w.headsPerGroup[0] ? 0 : 1;
		}

		/**
		 * RMS-normalizes every consecutive {@code size} elements of {@code x} by their own
		 * root mean square and scales element-wise by {@code weight}.
		 */
		private static double[] rmsnorm(double[] x, double[] weight, double epsilon, int size) {
			double[] out = new double[x.length];
			for (int row = 0; row < x.length / size; row++) {
				double sumSq = 0;
				for (int i = 0; i < size; i++) {
					sumSq += x[row * size + i] * x[row * size + i];
				}
				double scale = 1.0 / Math.sqrt(sumSq / size + epsilon);
				for (int i = 0; i < size; i++) {
					out[row * size + i] = x[row * size + i] * scale * weight[row * size + i];
				}
			}
			return out;
		}

		/** Dense projection {@code weights @ x + bias} with weights of shape {@code [nodes, x.length]}. */
		private static double[] project(PackedCollection weights, PackedCollection bias, double[] x, int nodes) {
			double[] wv = weights.toArray();
			double[] b = bias == null ? null : bias.toArray();
			double[] out = new double[nodes];
			for (int n = 0; n < nodes; n++) {
				double acc = b == null ? 0 : b[n];
				for (int s = 0; s < x.length; s++) {
					acc += wv[n * x.length + s] * x[s];
				}
				out[n] = acc;
			}
			return out;
		}

		/**
		 * Rotates one head of {@code v} in place in the split-half layout: element {@code i}
		 * pairs with element {@code i + headSize / 2}, and the pair is rotated by the angle
		 * {@code table} holds for {@code position} and frequency {@code i}.
		 */
		private void rotate(double[] v, int head, double[] table, int position) {
			int half = w.headSize / 2;
			int base = head * w.headSize;
			for (int i = 0; i < half; i++) {
				double cos = table[(position * half + i) * 2];
				double sin = table[(position * half + i) * 2 + 1];
				double a = v[base + i];
				double b = v[base + half + i];
				v[base + i] = a * cos - b * sin;
				v[base + half + i] = b * cos + a * sin;
			}
		}
	}
}
