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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Holds the layers of {@code /pdsl/sequence_attention.pdsl}, which
 * {@link AttentionFeatures#sequenceAttention} builds, to the Java assemblies they replaced —
 * parallel (full-sequence) multi-head self-attention with rotary position embeddings, bidirectional
 * or causal.
 *
 * <p>Every configuration is computed several ways over the same weights and input: by the feature
 * method {@code sequenceAttention}; by the asset layer built directly through {@link PdslLoader}
 * with the arguments its header documents; by fixed copies of the Java assemblies, with dense
 * projections, so that the comparison is independent of later changes to the feature method —
 * {@link Weights#operandAssembly}, the assembly {@code sequenceAttention} built before it loaded the
 * asset, whose key and value branches are operands of the attention products, and, for a
 * bidirectional configuration, the earlier {@link Weights#javaAssembly}, whose keys and values reach
 * the products through stores; and by a reference computed on the host in double precision
 * ({@link Weights#hostReference}), which uses no framework block and so pins the numerical contract
 * whatever builds the attention. The framework computations must agree to within float rounding and
 * the feature method must match the host reference.</p>
 *
 * <p>The configurations cover the three query/key normalization families (none, LayerNorm and
 * RMSNorm, with and without biases) crossed with plain and soft-capped scores, each bidirectional
 * and causal, the value padding mask and the key mask, the partial rotation of the Stable Audio
 * diffusion transformer and the full rotation of T5Gemma. The backward pass is pinned as well: the
 * gradients of the fused projection, the output projection and the input must agree between the
 * asset, the feature method and the operand assembly, and the key and value thirds of the fused
 * projection must receive a gradient through the asset at all. Further tests run several forward
 * passes through one compiled model, pass a projection factory that wraps the projections in
 * low-rank adapters, and supply only one of the two query/key normalization weights.</p>
 */
public class SequenceAttentionAssetTest extends TestSuiteBase implements AttentionFeatures, ModelTestFeatures {

	/** Classpath location of the parallel self-attention asset. */
	private static final String SEQUENCE_ATTENTION_ASSET = "/pdsl/sequence_attention.pdsl";

	/** Batch size the parallel attention supports. */
	private static final int BATCH = 1;

	/** Sequence length of the scaled-down configuration. */
	private static final int SEQ_LEN = 5;

	/** Head count: two, so the per-head reshape and permute are exercised. */
	private static final int HEADS = 2;

	/** Dimension per head. */
	private static final int DIM_HEAD = 8;

	/** Model dimension. */
	private static final int DIM = HEADS * DIM_HEAD;

	/** Epsilon of the query/key normalizations. */
	private static final double EPSILON = 1e-6;

	/** Agreement required between the asset, the feature method and the Java assembly. */
	private static final double ASSEMBLY_TOLERANCE = 1e-6;

	/** Agreement required between the feature method and the double-precision host reference. */
	private static final double HOST_TOLERANCE = 1e-4;

	/**
	 * Agreement required between two gradients of the fused projection, relative to the largest
	 * gradient magnitude of the third being compared.
	 */
	private static final double GRADIENT_TOLERANCE = 1e-5;

	/**
	 * Smallest largest-magnitude a third of the fused projection's gradient may have: below it the
	 * third receives no gradient at all.
	 */
	private static final double MINIMUM_GRADIENT = 1e-3;

	/** Valid positions of the padding mask: the last two positions are padding. */
	private static final int PADDING_VALID = 3;

	/** Valid keys of the key mask: the last key is masked. */
	private static final int KEYS_VALID = 4;

	/**
	 * The Stable Audio diffusion transformer's self-attention: a LayerNorm of every query and key
	 * row with biases, a rotation of the leading half of each head, no masks and plain scores.
	 */
	@Test(timeout = 300000)
	public void qkLayerNormMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_qk_layernorm", NormalizationType.LAYER, true, false,
				0.0, null, null);
	}

	/**
	 * The RMS-normalized diffusion transformer with a padding mask: an RMSNorm of every query and
	 * key row without biases, and the values of the last two positions zeroed.
	 */
	@Test(timeout = 300000)
	public void qkRmsNormWithPaddingMaskMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_qk_rmsnorm", NormalizationType.RMS, false, false,
				0.0, PADDING_VALID, null);
	}

	/**
	 * The T5Gemma encoder's self-attention: no query/key normalization, a full rotation of every
	 * head, soft-capped scores and a key mask removing the last key from every softmax.
	 */
	@Test(timeout = 300000)
	public void softcappedWithKeyMaskMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_softcapped", null, false, true, 2.0, null, KEYS_VALID);
	}

	/** Self-attention with no normalization, no masks and plain scores. */
	@Test(timeout = 300000)
	public void plainMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention", null, false, true, 0.0, null, null);
	}

	/** LayerNorm of the queries and keys with soft-capped scores and both masks. */
	@Test(timeout = 300000)
	public void qkLayerNormSoftcappedMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_qk_layernorm_softcapped", NormalizationType.LAYER, true, false,
				2.0, PADDING_VALID, KEYS_VALID);
	}

	/** RMSNorm of the queries and keys with biases, soft-capped scores and a key mask. */
	@Test(timeout = 300000)
	public void qkRmsNormWithBiasesSoftcappedMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_qk_rmsnorm_softcapped", NormalizationType.RMS, true, true,
				2.0, null, KEYS_VALID);
	}

	/**
	 * The key and value stores are rewritten on every forward pass: three passes through one
	 * compiled block, over two different sequences, each match the host reference for their own
	 * input, so no pass reads keys or values left by the one before.
	 */
	@Test(timeout = 300000)
	public void keyAndValueStoresAreRewrittenOnEveryForwardPass() {
		Weights w = new Weights(NormalizationType.LAYER, true, false);
		try (CompiledModel compiled = compile(w.feature(ProjectionFactory.dense(), null, null, 0.0))) {
			PackedCollection first = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
			PackedCollection second = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
			double[] firstOutput = compiled.forward(first).doubleStream().toArray();
			double[] secondOutput = compiled.forward(second).doubleStream().toArray();
			double[] firstAgain = compiled.forward(first).doubleStream().toArray();

			double[] firstExpected = w.hostReference(first.doubleStream().toArray(), null, null, 0.0);
			double[] secondExpected = w.hostReference(second.doubleStream().toArray(), null, null, 0.0);
			assertClose("first pass", firstExpected, firstOutput, HOST_TOLERANCE);
			assertClose("second pass", secondExpected, secondOutput, HOST_TOLERANCE);
			assertClose("third pass", firstExpected, firstAgain, HOST_TOLERANCE);
			assertTrue("the two sequences must attend differently",
					maxDifference(firstOutput, secondOutput) > 1e-3);
		}
	}

	/**
	 * The two projections are the layers of the projection factory the caller passes: a low-rank
	 * adapter factory wraps exactly the fused query/key/value projection and the output projection,
	 * in that order. Their {@code B} matrices start at zero, so the adapted attention first matches
	 * the dense one; once the query/key/value adapter's {@code B} is non-zero, the same compiled
	 * block computes a different attention, which shows the adapter's weights are read by the graph.
	 */
	@Test(timeout = 300000)
	public void adaptedProjectionsComeFromTheProjectionFactory() {
		Weights w = new Weights(NormalizationType.LAYER, true, false);
		List<LoRALinear> adapters = new ArrayList<>();
		ProjectionFactory adapted = ProjectionFactory.lora(new AdapterConfig().rank(2).alpha(4.0)
				.targets(AdapterConfig.TargetLayer.SELF_ATTENTION_QKV,
						AdapterConfig.TargetLayer.SELF_ATTENTION_OUT), adapters);

		try (CompiledModel compiled = compile(w.feature(adapted, null, null, 0.0))) {
			assertEquals("one adapter per projection", 2, adapters.size());
			assertEquals("the fused projection is adapted first", 3 * DIM,
					adapters.get(0).getOutputShape().getTotalSize() / SEQ_LEN);
			assertEquals("the output projection is adapted second", DIM,
					adapters.get(1).getOutputShape().getTotalSize() / SEQ_LEN);

			PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
			double[] untrained = compiled.forward(input).doubleStream().toArray();
			double[] dense = run(w.feature(ProjectionFactory.dense(), null, null, 0.0), input);
			assertClose("adapters with zero B", dense, untrained, ASSEMBLY_TOLERANCE);

			adapters.get(0).getLoraB().fill(0.05);
			double[] trained = compiled.forward(input).doubleStream().toArray();
			assertTrue("a non-zero adapter must change the attention",
					maxDifference(untrained, trained) > 1e-4);
		}
	}

	/**
	 * Query/key normalization is applied to both or neither: supplying a key normalization weight
	 * without a query one, or a query weight without a key one, is rejected rather than built into
	 * a block that silently skips the key normalization or normalizes the keys incorrectly.
	 */
	@Test(timeout = 120000)
	public void queryAndKeyNormalizationWeightsAreRequiredTogether() {
		Weights w = new Weights(NormalizationType.LAYER, true, false);
		try {
			sequenceAttention(BATCH, SEQ_LEN, DIM, HEADS, w.qkv, w.out,
					null, null, w.kNormWeight, w.kNormBias, w.invFreq, ProjectionFactory.dense(),
					NormalizationType.LAYER, null, null, 0.0);
			Assert.fail("sequenceAttention should reject a key normalization without a query normalization");
		} catch (IllegalArgumentException expected) {
			// expected
		}

		try {
			sequenceAttention(BATCH, SEQ_LEN, DIM, HEADS, w.qkv, w.out,
					w.qNormWeight, w.qNormBias, null, null, w.invFreq, ProjectionFactory.dense(),
					NormalizationType.LAYER, null, null, 0.0);
			Assert.fail("sequenceAttention should reject a query normalization without a key normalization");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * The asset carries gradients back through its key and value branches. A fixed output gradient
	 * is propagated backward through the {@code sequence_attention_qk_layernorm} layer and through
	 * {@code sequenceAttention}, and each third of the fused query/key/value projection must receive
	 * the same gradient from both. The key and value thirds reach the output only through the two
	 * attention products, so they receive a gradient only if the products read the key and value
	 * branches as operands of the graph rather than as stored copies.
	 */
	@Test(timeout = 600000)
	public void assetCarriesGradientsIntoKeysAndValues() {
		Weights w = new Weights(NormalizationType.LAYER, true, false);
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
		PackedCollection outputGradient = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		double[] feature = backpropagate(w.feature(ProjectionFactory.dense(), null, null, 0.0),
				input, outputGradient).projection();
		double[] asset = backpropagate(w.asset("sequence_attention_qk_layernorm", null, null, 0.0),
				input, outputGradient).projection();

		String[] thirds = { "query", "key", "value" };
		int size = DIM * DIM;
		for (int t = 0; t < thirds.length; t++) {
			double[] expected = Arrays.copyOfRange(feature, t * size, (t + 1) * size);
			double[] actual = Arrays.copyOfRange(asset, t * size, (t + 1) * size);
			double magnitude = maxDifference(expected, new double[size]);
			assertTrue("the " + thirds[t] + " third of the projection receives no gradient through "
					+ "sequenceAttention", magnitude > MINIMUM_GRADIENT);
			assertTrue("the " + thirds[t] + " third of the projection receives no gradient through the asset",
					maxDifference(actual, new double[size]) > MINIMUM_GRADIENT);
			assertClose(thirds[t] + " third: asset vs sequenceAttention gradient", expected, actual,
					GRADIENT_TOLERANCE * Math.max(1.0, magnitude));
		}
	}

	/**
	 * Causal self-attention without query/key normalization, with every feature of a head rotated
	 * and no masks: the self-attention of a decoder language model.
	 */
	@Test(timeout = 300000)
	public void causalMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_causal", null, false, true, 0.0, null, null, true);
	}

	/** Causal self-attention with soft-capped scores and a padding mask. */
	@Test(timeout = 300000)
	public void causalSoftcappedMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_softcapped_causal", null, false, true, 2.0, PADDING_VALID, null, true);
	}

	/** Causal self-attention with a LayerNorm of every query and key row and a key mask. */
	@Test(timeout = 300000)
	public void causalQkLayerNormMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_qk_layernorm_causal", NormalizationType.LAYER, true, false,
				0.0, null, KEYS_VALID, true);
	}

	/** Causal self-attention with a LayerNorm of the queries and keys, soft-capped scores and both masks. */
	@Test(timeout = 300000)
	public void causalQkLayerNormSoftcappedMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_qk_layernorm_softcapped_causal", NormalizationType.LAYER, true, false,
				2.0, PADDING_VALID, KEYS_VALID, true);
	}

	/** Causal self-attention with an RMSNorm of the queries and keys without biases and a padding mask. */
	@Test(timeout = 300000)
	public void causalQkRmsNormMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_qk_rmsnorm_causal", NormalizationType.RMS, false, false,
				0.0, PADDING_VALID, null, true);
	}

	/** Causal self-attention with an RMSNorm of the queries and keys with biases, soft-capped scores and a key mask. */
	@Test(timeout = 300000)
	public void causalQkRmsNormSoftcappedMatchesJavaAssemblyAndHostReference() {
		assertMatches("sequence_attention_qk_rmsnorm_softcapped_causal", NormalizationType.RMS, true, true,
				2.0, null, KEYS_VALID, true);
	}

	/**
	 * The backward pass of the Stable Audio diffusion transformer's self-attention (a LayerNorm of
	 * the queries and keys, a padding mask) delivers the same gradients through the asset, through
	 * {@code sequenceAttention} and through the operand assembly.
	 */
	@Test(timeout = 600000)
	public void qkLayerNormGradientsMatchJavaAssembly() {
		assertGradientsMatch("sequence_attention_qk_layernorm", NormalizationType.LAYER, true, false,
				0.0, PADDING_VALID, null, false);
	}

	/**
	 * The backward pass of a decoder language model's causal self-attention delivers the same
	 * gradients through the asset, through {@code sequenceAttention} and through the operand assembly.
	 */
	@Test(timeout = 600000)
	public void causalGradientsMatchJavaAssembly() {
		assertGradientsMatch("sequence_attention_causal", null, false, true, 0.0, null, null, true);
	}

	/**
	 * The backward pass of the T5Gemma encoder's self-attention (soft-capped scores, a key mask)
	 * delivers the same gradients through the asset, through {@code sequenceAttention} and through
	 * the operand assembly.
	 */
	@Test(timeout = 600000)
	public void softcappedGradientsMatchJavaAssembly() {
		assertGradientsMatch("sequence_attention_softcapped", null, false, true, 2.0, null, KEYS_VALID, false);
	}

	/**
	 * Computes one bidirectional configuration five ways and checks that the asset, the feature
	 * method and the two Java assemblies agree to within float rounding, and that the feature method
	 * matches the host reference.
	 *
	 * @param layer      the asset layer that covers the configuration
	 * @param qkNorm     the query/key normalization family, or {@code null} for none
	 * @param biases     whether the normalization has biases
	 * @param fullRotary true to rotate every feature of a head, false for the leading half
	 * @param softcap      the logit soft-cap, or {@code 0} for none
	 * @param paddingValid the number of leading positions the padding mask marks valid, or
	 *                     {@code null} for no padding mask
	 * @param keysValid    the number of leading keys the key mask marks valid, or {@code null} for no
	 *                     key mask
	 */
	private void assertMatches(String layer, NormalizationType qkNorm, boolean biases, boolean fullRotary,
							   double softcap, Integer paddingValid, Integer keysValid) {
		assertMatches(layer, qkNorm, biases, fullRotary, softcap, paddingValid, keysValid, false);
	}

	/**
	 * Computes one configuration several ways and checks that they agree: the asset layer, the
	 * feature method and the {@link Weights#operandAssembly operand assembly} to within float
	 * rounding, the feature method to the host reference, and, for a bidirectional configuration,
	 * the asset and the feature method also to the {@link Weights#javaAssembly stored-copy assembly}.
	 *
	 * @param layer        the asset layer that covers the configuration
	 * @param qkNorm       the query/key normalization family, or {@code null} for none
	 * @param biases       whether the normalization has biases
	 * @param fullRotary   true to rotate every feature of a head, false for the leading half
	 * @param softcap      the logit soft-cap, or {@code 0} for none
	 * @param paddingValid the number of leading positions the padding mask marks valid, or
	 *                     {@code null} for no padding mask
	 * @param keysValid    the number of leading keys the key mask marks valid, or {@code null} for no
	 *                     key mask
	 * @param causal       whether each position attends only to itself and earlier positions
	 */
	private void assertMatches(String layer, NormalizationType qkNorm, boolean biases, boolean fullRotary,
							   double softcap, Integer paddingValid, Integer keysValid, boolean causal) {
		Weights w = new Weights(qkNorm, biases, fullRotary);
		PackedCollection padding = paddingValid == null ? null : validPrefix(paddingValid);
		PackedCollection keys = keysValid == null ? null : validPrefix(keysValid);
		Producer<PackedCollection> paddingMask = padding == null ? null : cp(padding);
		Producer<PackedCollection> keyMask = keys == null ? null : cp(keys);
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		double[] feature = run(w.feature(ProjectionFactory.dense(), paddingMask, keyMask, softcap, causal), input);
		double[] operands = run(w.operandAssembly(paddingMask, keyMask, softcap, causal), input);
		double[] asset = run(w.asset(layer, padding, keys, softcap), input);
		double[] host = w.hostReference(input.doubleStream().toArray(),
				padding == null ? null : padding.doubleStream().toArray(),
				keys == null ? null : keys.doubleStream().toArray(), softcap, causal);

		if (!causal) {
			double[] assembly = run(w.javaAssembly(paddingMask, keyMask, softcap), input);
			assertClose(layer + ": asset vs Java assembly", assembly, asset, ASSEMBLY_TOLERANCE);
			assertClose(layer + ": sequenceAttention vs Java assembly", assembly, feature, ASSEMBLY_TOLERANCE);
		}

		assertClose(layer + ": asset vs operand assembly", operands, asset, ASSEMBLY_TOLERANCE);
		assertClose(layer + ": sequenceAttention vs operand assembly", operands, feature, ASSEMBLY_TOLERANCE);
		assertClose(layer + ": sequenceAttention vs host reference", host, feature, HOST_TOLERANCE);
	}

	/**
	 * Propagates one output gradient backward through one configuration three ways — the asset
	 * layer, the feature method and the {@link Weights#operandAssembly operand assembly} — and checks
	 * that the gradients of the fused projection weight, the output projection weight and the input
	 * agree, each relative to its largest magnitude.
	 *
	 * @param layer        the asset layer that covers the configuration
	 * @param qkNorm       the query/key normalization family, or {@code null} for none
	 * @param biases       whether the normalization has biases
	 * @param fullRotary   true to rotate every feature of a head, false for the leading half
	 * @param softcap      the logit soft-cap, or {@code 0} for none
	 * @param paddingValid the number of leading positions the padding mask marks valid, or
	 *                     {@code null} for no padding mask
	 * @param keysValid    the number of leading keys the key mask marks valid, or {@code null} for no
	 *                     key mask
	 * @param causal       whether each position attends only to itself and earlier positions
	 */
	private void assertGradientsMatch(String layer, NormalizationType qkNorm, boolean biases, boolean fullRotary,
									  double softcap, Integer paddingValid, Integer keysValid, boolean causal) {
		Weights w = new Weights(qkNorm, biases, fullRotary);
		PackedCollection padding = paddingValid == null ? null : validPrefix(paddingValid);
		PackedCollection keys = keysValid == null ? null : validPrefix(keysValid);
		Producer<PackedCollection> paddingMask = padding == null ? null : cp(padding);
		Producer<PackedCollection> keyMask = keys == null ? null : cp(keys);
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();
		PackedCollection outputGradient = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		Backward operands = backpropagate(w.operandAssembly(paddingMask, keyMask, softcap, causal),
				input, outputGradient);
		Map<String, Backward> builds = new HashMap<>();
		builds.put("asset", backpropagate(w.asset(layer, padding, keys, softcap), input, outputGradient));
		builds.put("sequenceAttention", backpropagate(
				w.feature(ProjectionFactory.dense(), paddingMask, keyMask, softcap, causal), input, outputGradient));

		builds.forEach((name, backward) -> {
			assertGradientClose(layer + ": " + name + " fused projection gradient",
					operands.projection(), backward.projection());
			assertGradientClose(layer + ": " + name + " output projection gradient",
					operands.output(), backward.output());
			assertGradientClose(layer + ": " + name + " input gradient", operands.input, backward.input);
		});
	}

	/**
	 * Asserts that two gradients agree to within {@link #GRADIENT_TOLERANCE} of the larger of one and
	 * the expected gradient's largest magnitude, and that the expected gradient is not trivially zero.
	 *
	 * @param label    a label for the messages
	 * @param expected the reference gradient
	 * @param actual   the gradient under test
	 */
	private void assertGradientClose(String label, double[] expected, double[] actual) {
		double magnitude = maxDifference(expected, new double[expected.length]);
		assertTrue(label + " is trivially zero", magnitude > MINIMUM_GRADIENT);
		assertClose(label, expected, actual, GRADIENT_TOLERANCE * Math.max(1.0, magnitude));
	}

	/**
	 * A {@code [batch, seq_len]} validity mask whose first {@code valid} positions are valid (one) and
	 * whose remaining positions are masked (zero), produced by the computation graph.
	 *
	 * @param valid the number of leading valid positions
	 * @return the mask
	 */
	private PackedCollection validPrefix(int valid) {
		PackedCollection mask = new PackedCollection(shape(BATCH, SEQ_LEN));
		lessThan(integers(0, SEQ_LEN), c((double) valid), c(1.0), c(0.0))
				.into(mask.traverseEach()).evaluate();
		return mask;
	}

	/**
	 * Compiles a block that maps a {@code [batch, seq_len, dim]} sequence to the same shape.
	 *
	 * @param block the block
	 * @return the compiled model
	 */
	private CompiledModel compile(Block block) {
		Model model = new Model(shape(BATCH, SEQ_LEN, DIM));
		model.add(block);
		return model.compile(false);
	}

	/**
	 * Compiles a block and runs one forward pass.
	 *
	 * @param block the block
	 * @param input the sequence
	 * @return the output, flattened
	 */
	private double[] run(Block block, PackedCollection input) {
		try (CompiledModel compiled = compile(block)) {
			return compiled.forward(input).doubleStream().toArray();
		}
	}

	/**
	 * Compiles a block with its backward pass, runs one forward pass over {@code input} and one
	 * backward pass of {@code outputGradient}, and returns the gradients the backward pass delivers.
	 *
	 * @param block          the attention block
	 * @param input          the sequence
	 * @param outputGradient the gradient of the loss with respect to the block's output
	 * @return the gradients of the two projection weights and of the input
	 */
	private Backward backpropagate(Block block, PackedCollection input, PackedCollection outputGradient) {
		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(shape(BATCH, SEQ_LEN, DIM), gradientRecorder(recorded));
		model.add(block);
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			double[] inputGradient = compiled.backward(outputGradient).doubleStream().toArray();
			List<double[]> weightGradients = new ArrayList<>();
			recorded.forEach(gradient -> weightGradients.add(gradient.doubleStream().toArray()));
			return new Backward(weightGradients, inputGradient);
		}
	}

	/**
	 * The gradients one backward pass delivers through an attention block: one to every weight the
	 * block trains, among them the fused query/key/value projection weight ({@code [3 * dim, dim]},
	 * query rows first) and the output projection weight ({@code [dim, dim]}), and one to the input
	 * sequence.
	 */
	private static final class Backward {
		/** The weight gradients, one per weight, flattened. */
		private final List<double[]> weights;

		/** Gradient of the input sequence. */
		private final double[] input;

		/**
		 * Holds the gradients of one backward pass.
		 *
		 * @param weights the weight gradients, one per weight, flattened
		 * @param input   gradient of the input sequence
		 */
		Backward(List<double[]> weights, double[] input) {
			this.weights = weights;
			this.input = input;
		}

		/**
		 * Returns the gradient of the one weight with the given number of values.
		 *
		 * @param size        the number of values of the weight
		 * @param description the weight, for the failure message
		 * @return the weight's gradient
		 */
		double[] weight(int size, String description) {
			return weights.stream()
					.filter(gradient -> gradient.length == size)
					.findFirst()
					.orElseThrow(() -> new AssertionError("No gradient recorded for the " + description));
		}

		/** Returns the gradient of the fused query/key/value projection weight, query rows first. */
		double[] projection() {
			return weight(3 * DIM * DIM, "fused projection");
		}

		/** Returns the gradient of the output projection weight. */
		double[] output() {
			return weight(DIM * DIM, "output projection");
		}
	}

	/**
	 * Asserts that two outputs agree element by element and logs their largest difference.
	 *
	 * @param label     a label for the messages
	 * @param expected  the reference output
	 * @param actual    the output under test
	 * @param tolerance the largest permitted absolute difference
	 */
	private void assertClose(String label, double[] expected, double[] actual, double tolerance) {
		assertEquals(label + " size", expected.length, actual.length);
		double difference = maxDifference(expected, actual);
		log(label + " maxDifference=" + difference);
		assertTrue(label + " differs by " + difference, difference <= tolerance);
	}

	/**
	 * The largest element-wise difference between two outputs of the same size.
	 *
	 * @param a the first output
	 * @param b the second output
	 * @return the largest absolute difference
	 */
	private static double maxDifference(double[] a, double[] b) {
		double difference = 0.0;
		for (int i = 0; i < a.length; i++) {
			difference = Math.max(difference, Math.abs(a[i] - b[i]));
		}
		return difference;
	}

	/**
	 * A small random weight, scaled so that the projections stay near unit magnitude and the
	 * softmax is not saturated.
	 *
	 * @param dims the weight dimensions
	 * @return the weight
	 */
	private PackedCollection random(int... dims) {
		PackedCollection value = new PackedCollection(shape(dims)).randnFill();
		return cp(value).multiply(0.25).into(value.traverseEach()).evaluate();
	}

	/**
	 * One set of random self-attention weights and the ways of computing the attention over them.
	 */
	private final class Weights {
		/** The query/key normalization family, or {@code null} for none. */
		private final NormalizationType qkNorm;

		/** Fused query/key/value projection, {@code [3 * dim, dim]}. */
		private final PackedCollection qkv = random(3 * DIM, DIM);

		/** Output projection, {@code [dim, dim]}. */
		private final PackedCollection out = random(DIM, DIM);

		/** Query normalization scale, or {@code null} without normalization. */
		private final PackedCollection qNormWeight;

		/** Query normalization shift, or {@code null}. */
		private final PackedCollection qNormBias;

		/** Key normalization scale, or {@code null} without normalization. */
		private final PackedCollection kNormWeight;

		/** Key normalization shift, or {@code null}. */
		private final PackedCollection kNormBias;

		/** Rotary inverse frequencies: one per rotated feature pair. */
		private final PackedCollection invFreq;

		/**
		 * Creates the weights of one configuration.
		 *
		 * @param qkNorm     the query/key normalization family, or {@code null} for none
		 * @param biases     whether the normalization has biases
		 * @param fullRotary true to rotate every feature of a head, false for the leading half
		 */
		Weights(NormalizationType qkNorm, boolean biases, boolean fullRotary) {
			this.qkNorm = qkNorm;
			boolean normalized = qkNorm != null;
			this.qNormWeight = normalized ? new PackedCollection(shape(DIM_HEAD)).randnFill() : null;
			this.qNormBias = normalized && biases ? random(DIM_HEAD) : null;
			this.kNormWeight = normalized ? new PackedCollection(shape(DIM_HEAD)).randnFill() : null;
			this.kNormBias = normalized && biases ? random(DIM_HEAD) : null;
			this.invFreq = fullRotary ? PackedCollection.of(1.0, 0.3, 0.1, 0.03)
					: PackedCollection.of(0.5, 0.05);
		}

		/**
		 * The attention built by the feature method.
		 *
		 * @param projections the projection factory
		 * @param paddingMask the padding mask, or {@code null}
		 * @param keyMask     the key mask, or {@code null}
		 * @param softcap     the logit soft-cap, or {@code 0}
		 * @return the block
		 */
		Block feature(ProjectionFactory projections, Producer<PackedCollection> paddingMask,
					  Producer<PackedCollection> keyMask, double softcap) {
			return feature(projections, paddingMask, keyMask, softcap, false);
		}

		/**
		 * The attention built by the feature method, bidirectional or causal.
		 *
		 * @param projections the projection factory
		 * @param paddingMask the padding mask, or {@code null}
		 * @param keyMask     the key mask, or {@code null}
		 * @param softcap     the logit soft-cap, or {@code 0}
		 * @param causal      whether each position attends only to itself and earlier positions
		 * @return the block
		 */
		Block feature(ProjectionFactory projections, Producer<PackedCollection> paddingMask,
					  Producer<PackedCollection> keyMask, double softcap, boolean causal) {
			return sequenceAttention(BATCH, SEQ_LEN, DIM, HEADS, qkv, out,
					qNormWeight, qNormBias, kNormWeight, kNormBias, invFreq, projections,
					qkNorm, paddingMask, keyMask, softcap, causal);
		}

		/**
		 * A fixed copy of the Java assembly {@code sequenceAttention} built before the operand
		 * assembly, with dense projections: the key and value branches write the stores the attention
		 * reads back as constant tensors, so this assembly computes the forward pass only. It is the
		 * reference the forward pass of every bidirectional layer is held to.
		 *
		 * @param paddingMask the padding mask, or {@code null}
		 * @param keyMask     the key mask, or {@code null}
		 * @param softcap     the logit soft-cap, or {@code 0}
		 * @return the block
		 */
		Block javaAssembly(Producer<PackedCollection> paddingMask, Producer<PackedCollection> keyMask,
						   double softcap) {
			ProjectionFactory projectionFactory = ProjectionFactory.dense();
			TraversalPolicy inputShape = shape(BATCH, SEQ_LEN, DIM);
			TraversalPolicy headShape = shape(BATCH, HEADS, SEQ_LEN, DIM_HEAD);

			SequentialBlock attention = new SequentialBlock(inputShape);
			attention.add(projectionFactory.create(inputShape, qkv,
					AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));

			attention.reshape(BATCH, SEQ_LEN, 3, DIM);
			List<Block> sections = attention.split(shape(BATCH, SEQ_LEN, 1, DIM), 0);
			SequentialBlock q = (SequentialBlock) sections.get(0).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			SequentialBlock k = (SequentialBlock) sections.get(1).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			SequentialBlock v = (SequentialBlock) sections.get(2).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			q.permute(0, 2, 1, 3);
			k.permute(0, 2, 1, 3);
			v.permute(0, 2, 1, 3);

			if (qNormWeight != null) {
				q.add(norm(qkNorm, qNormWeight, qNormBias, EPSILON));
				k.add(norm(qkNorm, kNormWeight, kNormBias, EPSILON));
			}

			q.add(applyRotaryPositionEmbedding(headShape, invFreq));
			k.add(applyRotaryPositionEmbedding(headShape, invFreq));

			if (paddingMask != null) {
				v.add(scale(headShape, 2, paddingMask));
			}

			PackedCollection kTensor = new PackedCollection(headShape);
			PackedCollection vTensor = new PackedCollection(headShape);
			k.andThen(into(kTensor));
			v.andThen(into(vTensor));

			q.add(scaledDotProductAttention(BATCH, SEQ_LEN, SEQ_LEN, HEADS, DIM_HEAD, kTensor, vTensor,
					null, softcap, keyMask));
			q.permute(0, 2, 1, 3).reshape(BATCH, SEQ_LEN, DIM);
			q.add(projectionFactory.create(shape(BATCH, SEQ_LEN, DIM), out,
					AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));
			return attention;
		}

		/**
		 * A fixed copy of the Java assembly {@code sequenceAttention} built before it loaded the asset,
		 * with dense projections. The key and value branches split off the fused projection are the
		 * second operands of the two attention products, so a backward pass carries gradients into
		 * them, and between the products the scores are scaled, soft-capped, key-masked and, for
		 * causal attention, causally masked before the softmax. It is the reference the forward and
		 * backward passes of every layer, bidirectional or causal, are held to.
		 *
		 * @param paddingMask the padding mask, or {@code null}
		 * @param keyMask     the key mask, or {@code null}
		 * @param softcap     the logit soft-cap, or {@code 0}
		 * @param causal      whether each position attends only to itself and earlier positions
		 * @return the block
		 */
		Block operandAssembly(Producer<PackedCollection> paddingMask, Producer<PackedCollection> keyMask,
							  double softcap, boolean causal) {
			TraversalPolicy sequenceShape = shape(BATCH, SEQ_LEN, DIM);
			TraversalPolicy rowShape = shape(BATCH, HEADS, SEQ_LEN, DIM_HEAD);
			TraversalPolicy scoreShape = shape(BATCH, HEADS, SEQ_LEN, SEQ_LEN);

			SequentialBlock queries = new SequentialBlock(sequenceShape);
			queries.add(ProjectionFactory.dense().create(sequenceShape, qkv,
					AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));
			queries.reshape(BATCH, SEQ_LEN, 3, DIM);
			List<Block> thirds = queries.split(shape(BATCH, SEQ_LEN, 1, DIM), 0);
			SequentialBlock keyRows = (SequentialBlock) thirds.get(1).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			SequentialBlock valueRows = (SequentialBlock) thirds.get(2).reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			queries.reshape(BATCH, SEQ_LEN, HEADS, DIM_HEAD);
			queries.permute(0, 2, 1, 3);
			keyRows.permute(0, 2, 1, 3);
			valueRows.permute(0, 2, 1, 3);

			if (qNormWeight != null) {
				queries.add(norm(qkNorm, qNormWeight, qNormBias, EPSILON));
				keyRows.add(norm(qkNorm, kNormWeight, kNormBias, EPSILON));
			}
			queries.add(applyRotaryPositionEmbedding(rowShape, invFreq));
			keyRows.add(applyRotaryPositionEmbedding(rowShape, invFreq));
			if (paddingMask != null) {
				valueRows.add(scale(rowShape, 2, paddingMask));
			}

			SequentialBlock attend = new SequentialBlock(rowShape);
			attend.add(compose("qkMatmul", rowShape, keyRows.getOutputShape(), scoreShape, keyRows,
					(q, keys) -> scaledDotProduct(c(q), c(keys), true)));
			attend.add(scale(1.0 / Math.sqrt(DIM_HEAD)));
			if (softcap > 0.0) {
				attend.add(layer("logitSoftcap", scoreShape, scoreShape,
						logits -> tanh(c(logits).multiply(1.0 / softcap)).multiply(softcap)));
			}
			if (keyMask != null) {
				CollectionProducer bias = c(keyMask).add(-1.0).multiply(MASKED_LOGIT_PENALTY);
				attend.add(layer("keyMask", scoreShape, scoreShape,
						logits -> add(c(logits), broadcast(scoreShape, 3, bias))));
			}
			if (causal) {
				attend.add(layer("causalMask", scoreShape, scoreShape,
						logits -> add(c(logits), causalLogitMask(scoreShape))));
			}
			SequentialBlock normalized = new SequentialBlock(scoreShape);
			normalized.add(softmax(scoreShape, true));
			attend.add(normalized);
			attend.add(compose("attnValues", scoreShape, valueRows.getOutputShape(), rowShape, valueRows,
					(scores, values) -> scaledDotProduct(c(scores), c(values))));
			queries.add(attend);

			queries.permute(0, 2, 1, 3);
			queries.reshape(BATCH, SEQ_LEN, DIM);
			queries.add(ProjectionFactory.dense().create(sequenceShape, out,
					AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));
			return queries;
		}

		/**
		 * The asset layer built directly through {@link PdslLoader}, bound as its header documents:
		 * the two projections as dense layers and all-ones masks where a mask is absent.
		 *
		 * @param layer   the asset layer that covers the configuration
		 * @param padding the padding mask, or {@code null}
		 * @param keys    the key mask, or {@code null}
		 * @param softcap the logit soft-cap, or {@code 0}
		 * @return the block
		 */
		Block asset(String layer, PackedCollection padding, PackedCollection keys, double softcap) {
			TraversalPolicy inputShape = shape(BATCH, SEQ_LEN, DIM);
			ProjectionFactory dense = ProjectionFactory.dense();

			Map<String, Object> args = new HashMap<>();
			args.put("batch", BATCH);
			args.put("seq_len", SEQ_LEN);
			args.put("heads", HEADS);
			args.put("head_dim", DIM_HEAD);
			args.put("qkv_projection", dense.create(inputShape, qkv, AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));
			args.put("out_projection", dense.create(inputShape, out, AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));
			args.put("inv_freq", invFreq);
			args.put("padding_mask", padding == null ? validPrefix(SEQ_LEN) : padding);
			args.put("key_mask", keys == null ? validPrefix(SEQ_LEN) : keys);
			args.put("q_norm_weight", qNormWeight);
			args.put("q_norm_bias", qNormBias);
			args.put("k_norm_weight", kNormWeight);
			args.put("k_norm_bias", kNormBias);
			args.put("softcap", softcap);

			PdslLoader loader = new PdslLoader();
			return loader.buildLayer(loader.parseResource(SEQUENCE_ATTENTION_ASSET), layer, inputShape, args);
		}

		/**
		 * Computes the attention on the host in double precision, directly from the definition:
		 * project, split into per-head queries, keys and values, normalize and rotate the queries and
		 * keys, mask the values, take the (soft-capped, key-masked) softmax of the scaled scores,
		 * weight the values, join the heads and project.
		 *
		 * @param x       the sequence, row-major {@code [seq_len, dim]}
		 * @param padding the padding mask, or {@code null}
		 * @param keys    the key mask, or {@code null}
		 * @param softcap the logit soft-cap, or {@code 0}
		 * @return the expected output, row-major {@code [seq_len, dim]}
		 */
		double[] hostReference(double[] x, double[] padding, double[] keys, double softcap) {
			return hostReference(x, padding, keys, softcap, false);
		}

		/**
		 * Computes the attention on the host in double precision as {@link #hostReference(double[],
		 * double[], double[], double)} does, bidirectional or causal: when causal, every key after the
		 * query's own position receives the masked-logit penalty before the softmax.
		 *
		 * @param x       the sequence, row-major {@code [seq_len, dim]}
		 * @param padding the padding mask, or {@code null}
		 * @param keys    the key mask, or {@code null}
		 * @param softcap the logit soft-cap, or {@code 0}
		 * @param causal  whether each position attends only to itself and earlier positions
		 * @return the expected output, row-major {@code [seq_len, dim]}
		 */
		double[] hostReference(double[] x, double[] padding, double[] keys, double softcap, boolean causal) {
			double[][][] projected = new double[3][HEADS * SEQ_LEN][DIM_HEAD];
			double[] wqkv = qkv.doubleStream().toArray();
			for (int s = 0; s < SEQ_LEN; s++) {
				for (int o = 0; o < 3 * DIM; o++) {
					double sum = 0;
					for (int i = 0; i < DIM; i++) {
						sum += x[s * DIM + i] * wqkv[o * DIM + i];
					}
					projected[o / DIM][(o % DIM) / DIM_HEAD * SEQ_LEN + s][o % DIM_HEAD] = sum;
				}
			}

			double[][] queries = projected[0];
			double[][] keyRows = projected[1];
			double[][] values = projected[2];
			for (int h = 0; h < HEADS; h++) {
				for (int s = 0; s < SEQ_LEN; s++) {
					int row = h * SEQ_LEN + s;
					if (qkNorm != null) {
						normalize(queries[row], qNormWeight, qNormBias);
						normalize(keyRows[row], kNormWeight, kNormBias);
					}
					rotate(queries[row], s);
					rotate(keyRows[row], s);
					for (int d = 0; d < DIM_HEAD; d++) {
						values[row][d] *= padding == null ? 1.0 : padding[s];
					}
				}
			}

			double[] merged = new double[SEQ_LEN * DIM];
			for (int h = 0; h < HEADS; h++) {
				for (int i = 0; i < SEQ_LEN; i++) {
					double[] weights = new double[SEQ_LEN];
					double max = Double.NEGATIVE_INFINITY;
					for (int j = 0; j < SEQ_LEN; j++) {
						double score = 0;
						for (int d = 0; d < DIM_HEAD; d++) {
							score += queries[h * SEQ_LEN + i][d] * keyRows[h * SEQ_LEN + j][d];
						}
						score /= Math.sqrt(DIM_HEAD);
						if (softcap > 0) {
							score = softcap * Math.tanh(score / softcap);
						}
						if (keys != null) {
							score += (keys[j] - 1.0) * MASKED_LOGIT_PENALTY;
						}
						if (causal && j > i) {
							score -= MASKED_LOGIT_PENALTY;
						}
						weights[j] = score;
						max = Math.max(max, score);
					}
					double total = 0;
					for (int j = 0; j < SEQ_LEN; j++) {
						weights[j] = Math.exp(weights[j] - max);
						total += weights[j];
					}
					for (int d = 0; d < DIM_HEAD; d++) {
						double context = 0;
						for (int j = 0; j < SEQ_LEN; j++) {
							context += weights[j] / total * values[h * SEQ_LEN + j][d];
						}
						merged[i * DIM + h * DIM_HEAD + d] = context;
					}
				}
			}

			double[] wout = out.doubleStream().toArray();
			double[] result = new double[SEQ_LEN * DIM];
			for (int s = 0; s < SEQ_LEN; s++) {
				for (int o = 0; o < DIM; o++) {
					double sum = 0;
					for (int i = 0; i < DIM; i++) {
						sum += merged[s * DIM + i] * wout[o * DIM + i];
					}
					result[s * DIM + o] = sum;
				}
			}
			return result;
		}

		/**
		 * Normalizes one head row in place with this configuration's normalization family: LayerNorm
		 * subtracts the mean and divides by the standard deviation, RMSNorm divides by the root mean
		 * square; both then scale and shift.
		 *
		 * @param row    the row
		 * @param weight the scale
		 * @param bias   the shift, or {@code null}
		 */
		private void normalize(double[] row, PackedCollection weight, PackedCollection bias) {
			double[] w = weight.doubleStream().toArray();
			double[] b = bias == null ? new double[DIM_HEAD] : bias.doubleStream().toArray();
			double mean = 0;
			if (qkNorm == NormalizationType.LAYER) {
				for (double value : row) {
					mean += value / DIM_HEAD;
				}
			}
			double spread = 0;
			for (double value : row) {
				spread += (value - mean) * (value - mean) / DIM_HEAD;
			}
			for (int d = 0; d < DIM_HEAD; d++) {
				row[d] = (row[d] - mean) / Math.sqrt(spread + EPSILON) * w[d] + b[d];
			}
		}

		/**
		 * Rotates one head row in place for its position: feature {@code i} is paired with feature
		 * {@code i + n} ({@code n} inverse frequencies) and the pair is rotated by
		 * {@code position * invFreq[i]}; the features beyond the first {@code 2 n} are left alone.
		 *
		 * @param row      the row
		 * @param position the row's position in the sequence
		 */
		private void rotate(double[] row, int position) {
			double[] frequencies = invFreq.doubleStream().toArray();
			int half = frequencies.length;
			double[] original = row.clone();
			for (int j = 0; j < 2 * half; j++) {
				double angle = position * frequencies[j % half];
				double partner = j < half ? -original[j + half] : original[j - half];
				row[j] = original[j] * Math.cos(angle) + partner * Math.sin(angle);
			}
		}
	}
}
