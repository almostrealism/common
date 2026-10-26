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

package org.almostrealism.ml.t5gemma;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pins the migration of the T5Gemma encoder layer to {@code /pdsl/t5gemma_encoder.pdsl}: the asset's
 * sub-layers and whole layer, and the {@link T5GemmaEncoder} that builds them, must compute what the
 * Java assembly they replaced computed — {@code residual(rmsnorm, sequenceAttention, rmsnorm)} followed
 * by {@code residual(gatedLinearFeedForward, rmsnorm)} — except for the one defect that assembly had,
 * which {@link #feedForwardPreNormUsesConfiguredEpsilon} demonstrates.
 *
 * <p>Three references are used, so that the pins outlive the deleted assembly:</p>
 * <ul>
 *   <li>a transplant of the Java assembly, built in this test from the same framework layers the
 *       encoder used ({@link #referenceSelfAttention} and {@link #referenceFeedForward}); the
 *       feed-forward transplant takes the epsilon of its pre-normalization as an argument, so it
 *       reproduces both the old behaviour and the corrected one;</li>
 *   <li>the asset itself, built directly with {@link PdslLoader} from arguments bound here rather than
 *       through the encoder's own binding code;</li>
 *   <li>{@link T5GemmaReferenceEncoder}, a double-precision host computation of the encoder written from
 *       the published T5Gemma definition and independent of every framework layer.</li>
 * </ul>
 *
 * <p>The configuration is the small two-layer encoder of {@link T5GemmaWeightFixture} — 16-wide hidden
 * states, two heads of eight, a 32-wide feed-forward, eight positions — so every stage runs with more
 * than one head, more than one layer and a prompt that leaves padded positions.</p>
 */
public class T5GemmaEncoderLayerAssetTest extends TestSuiteBase implements AttentionFeatures {

	/** The asset under test. */
	private static final String ASSET = "/pdsl/t5gemma_encoder.pdsl";

	/** Weight key prefix of the first encoder layer. */
	private static final String LAYER_ZERO = "encoder.layers.0";

	/** Number of compiled token positions. */
	private static final int LENGTH = 8;

	/** A prompt shorter than {@link #LENGTH}, so the last positions are padding. */
	private static final long[] PROMPT = {5, 7, 9};

	/** A second, longer prompt, encoded after {@link #PROMPT} by the same compiled graph. */
	private static final long[] LONGER_PROMPT = {11, 3, 22, 8, 31};

	/** Largest difference permitted between two framework computations of the same structure. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * Largest difference permitted between a framework computation and the double-precision host one;
	 * the single-precision encoder agrees with the host to about {@code 1e-6} on these magnitudes.
	 */
	private static final double HOST_TOLERANCE = 1e-5;

	/** Builder of the small configuration and its random weights. */
	private final T5GemmaWeightFixture fixture = new T5GemmaWeightFixture();

	/**
	 * The soft-capped self-attention sub-layer — RMS norm, attention over the prompt keys, RMS norm —
	 * computes the same values through the asset, built directly, and through the encoder's
	 * {@link T5GemmaEncoder#attentionBranch} as through the transplanted Java assembly.
	 */
	@Test(timeout = 300000)
	public void selfAttentionSubLayerMatchesJavaAssembly() {
		assertSelfAttentionParity(fixture.smallConfig(LENGTH), "t5gemma_self_attention_softcapped");
	}

	/**
	 * With the soft-cap turned off (a cap of zero), the plain self-attention sub-layer computes the same
	 * values through the asset and the encoder as the transplanted Java assembly, which selected the
	 * attention layer without the soft-cap.
	 */
	@Test(timeout = 300000)
	public void uncappedSelfAttentionSubLayerMatchesJavaAssembly() {
		assertSelfAttentionParity(uncappedConfig(), "t5gemma_self_attention");
	}

	/**
	 * The feed-forward sub-layer — RMS norm, GELU-gated feed-forward, RMS norm — computes the same values
	 * through the asset, built directly, and through {@link T5GemmaEncoder#feedForwardBranch} as through
	 * the transplanted Java assembly.
	 *
	 * <p>The hidden states have a root mean square near ten, at which the epsilon of an RMS norm is
	 * invisible in single precision: the assembly's pre-normalization epsilon (see
	 * {@link #feedForwardPreNormUsesConfiguredEpsilon}) changes the normalized activations by less than
	 * one part in ten million there, so this test compares the structure alone.</p>
	 */
	@Test(timeout = 300000)
	public void feedForwardSubLayerMatchesJavaAssembly() {
		T5GemmaConfig config = fixture.smallConfig(LENGTH);
		Map<String, PackedCollection> weights = fixture.weights(config, 7);
		T5GemmaEncoder encoder = new T5GemmaEncoder(config, new StateDictionary(weights));

		try {
			TraversalPolicy blockShape = blockShape(config);
			PackedCollection input = hiddenStates(config, 11, 10.0);

			PackedCollection reference = run(referenceFeedForward(config, weights, config.getNormEpsilon()), input);
			PackedCollection asset = run(asset("t5gemma_feed_forward", blockShape,
					assetArguments(config, weights, null)), input);
			PackedCollection encoderBranch = run(encoder.feedForwardBranch(blockShape, LAYER_ZERO), input);

			assertClose("asset t5gemma_feed_forward vs Java assembly", reference, asset);
			assertClose("T5GemmaEncoder.feedForwardBranch vs Java assembly", reference, encoderBranch);
		} finally {
			encoder.destroy();
		}
	}

	/**
	 * The pre-normalization of the feed-forward sub-layer uses the configured RMS-norm epsilon, as the
	 * other three norms of the layer and the final norm do, and as T5Gemma itself does (every norm of a
	 * T5Gemma layer is built with the checkpoint's {@code rms_norm_eps}).
	 *
	 * <p>This is the defect the migration fixes. The Java assembly built this norm inside
	 * {@code FeedForwardFeatures.gatedLinearFeedForward}, which offers no epsilon parameter and
	 * normalizes with the default of {@code 1e-5}, so the encoder ran the norm at {@code 1e-5} while
	 * {@link T5GemmaConfig#getNormEpsilon()} documents {@code 1e-6} as the epsilon of every RMS norm.
	 * The hidden states here have a root mean square near {@code 1e-3}, small enough that the two
	 * epsilons give clearly different activations; the test first checks that they do, so it cannot
	 * pass vacuously, and then requires the encoder's sub-layer to agree with the configured epsilon.
	 * Against the Java assembly this test fails; through the asset it passes.</p>
	 */
	@Test(timeout = 300000)
	public void feedForwardPreNormUsesConfiguredEpsilon() {
		T5GemmaConfig config = fixture.smallConfig(LENGTH);
		Map<String, PackedCollection> weights = fixture.weights(config, 7);
		T5GemmaEncoder encoder = new T5GemmaEncoder(config, new StateDictionary(weights));

		try {
			TraversalPolicy blockShape = blockShape(config);
			PackedCollection input = hiddenStates(config, 13, 1e-3);

			PackedCollection configured = run(referenceFeedForward(config, weights, config.getNormEpsilon()), input);
			PackedCollection defaultEpsilon = run(referenceFeedForward(config, weights, 1e-5), input);
			double separation = largestDeviation(configured, defaultEpsilon);
			log("feed-forward sub-layer, configured vs 1e-5 pre-norm epsilon: largestDeviation=" + separation);
			assertTrue("the two epsilons must give distinguishable activations (largestDeviation="
					+ separation + ")", separation > 1000 * TOLERANCE);

			PackedCollection encoderBranch = run(encoder.feedForwardBranch(blockShape, LAYER_ZERO), input);
			assertClose("T5GemmaEncoder.feedForwardBranch vs the configured epsilon", configured, encoderBranch);
		} finally {
			encoder.destroy();
		}
	}

	/**
	 * The whole soft-capped encoder layer, built directly from the asset, matches the double-precision
	 * {@link T5GemmaReferenceEncoder}. The layer reads the prompt mask an encoder fills in
	 * {@link T5GemmaEncoder#loadPrompt(long[])}, and it is compiled once and run twice, with a different
	 * prompt loaded in between, so the test also shows that the mask is read on every forward pass
	 * rather than captured when the layer is built.
	 */
	@Test(timeout = 300000)
	public void encoderLayerMatchesHostReferenceAcrossPrompts() {
		T5GemmaConfig config = fixture.smallConfig(LENGTH);
		Map<String, PackedCollection> weights = fixture.weights(config, 17);
		T5GemmaReferenceEncoder host = new T5GemmaReferenceEncoder(config, weights);
		T5GemmaEncoder encoder = new T5GemmaEncoder(config, new StateDictionary(weights));
		PackedCollection input = hiddenStates(config, 19, 1.0);
		double[][] hiddenStates = new double[LENGTH][];
		for (int s = 0; s < LENGTH; s++) {
			hiddenStates[s] = input.toArray(s * config.getHiddenSize(), config.getHiddenSize());
		}

		Model model = new Model(blockShape(config));
		model.add(asset("t5gemma_encoder_layer_softcapped", blockShape(config),
				assetArguments(config, weights, cp(encoder.getAttentionMask()))));

		try (CompiledModel compiled = model.compile(false)) {
			for (long[] prompt : new long[][] {PROMPT, LONGER_PROMPT}) {
				encoder.loadPrompt(prompt);
				assertMatchesHost("t5gemma_encoder_layer_softcapped, prompt of " + prompt.length,
						host.layer(hiddenStates, 0, host.promptMask(prompt.length)), compiled.forward(input));
			}
		} finally {
			encoder.destroy();
		}
	}

	/**
	 * The whole encoder — scaled token embedding, both layers and the final RMS norm — matches the
	 * double-precision {@link T5GemmaReferenceEncoder} for two prompts encoded one after the other by the
	 * same compiled encoder.
	 */
	@Test(timeout = 300000)
	public void encoderMatchesHostReference() {
		assertEncoderMatchesHostReference(fixture.smallConfig(LENGTH));
	}

	/**
	 * With the soft-cap turned off, the whole encoder matches the double-precision
	 * {@link T5GemmaReferenceEncoder}, so the layer the encoder selects for that configuration is the
	 * plain-logit layer.
	 */
	@Test(timeout = 300000)
	public void uncappedEncoderMatchesHostReference() {
		assertEncoderMatchesHostReference(uncappedConfig());
	}

	/**
	 * Builds the self-attention sub-layer of the given configuration three ways — the transplanted Java
	 * assembly, the named layer of the asset, and {@link T5GemmaEncoder#attentionBranch} — and requires
	 * all three to agree.
	 *
	 * @param config the encoder configuration
	 * @param layer  the asset layer the configuration's sub-layer corresponds to
	 */
	private void assertSelfAttentionParity(T5GemmaConfig config, String layer) {
		Map<String, PackedCollection> weights = fixture.weights(config, 5);
		T5GemmaEncoder encoder = new T5GemmaEncoder(config, new StateDictionary(weights));

		try {
			encoder.loadPrompt(PROMPT);
			Producer<PackedCollection> keyMask = cp(encoder.getAttentionMask());
			TraversalPolicy blockShape = blockShape(config);
			PackedCollection input = hiddenStates(config, 3, 1.0);

			PackedCollection reference = run(referenceSelfAttention(config, weights, keyMask), input);
			PackedCollection asset = run(asset(layer, blockShape, assetArguments(config, weights, keyMask)), input);
			PackedCollection encoderBranch = run(encoder.attentionBranch(blockShape, LAYER_ZERO,
					weights.get("encoder.rotary.inv_freq")), input);

			assertClose("asset " + layer + " vs Java assembly", reference, asset);
			assertClose("T5GemmaEncoder.attentionBranch vs Java assembly", reference, encoderBranch);
		} finally {
			encoder.destroy();
		}
	}

	/**
	 * Encodes {@link #PROMPT} and then {@link #LONGER_PROMPT} with one encoder and requires each output
	 * to match {@link T5GemmaReferenceEncoder#encode}.
	 *
	 * @param config the encoder configuration
	 */
	private void assertEncoderMatchesHostReference(T5GemmaConfig config) {
		Map<String, PackedCollection> weights = fixture.weights(config, 23);
		T5GemmaReferenceEncoder host = new T5GemmaReferenceEncoder(config, weights);
		T5GemmaEncoder encoder = new T5GemmaEncoder(config, new StateDictionary(weights));

		try {
			for (long[] prompt : new long[][] {PROMPT, LONGER_PROMPT}) {
				assertMatchesHost("encoder, softcap " + config.getAttentionSoftcap() + ", prompt of " + prompt.length,
						host.encode(prompt), encoder.forward(prompt));
			}
		} finally {
			encoder.destroy();
		}
	}

	/**
	 * The self-attention sub-layer exactly as {@code T5GemmaEncoder.attentionBranch} assembled it in
	 * Java before the migration: RMS norm, the framework's full-sequence attention with the fused
	 * projection, no query/key normalization, an all-ones value mask, the prompt as the key mask and the
	 * configured soft-cap, then RMS norm.
	 *
	 * @param config  the encoder configuration
	 * @param weights the encoder weights
	 * @param keyMask the prompt mask
	 * @return the sub-layer
	 */
	private Block referenceSelfAttention(T5GemmaConfig config, Map<String, PackedCollection> weights,
										 Producer<PackedCollection> keyMask) {
		TraversalPolicy blockShape = blockShape(config);
		SequentialBlock branch = new SequentialBlock(blockShape);
		branch.add(rmsnorm(blockShape, weights.get(LAYER_ZERO + ".pre_self_attn_layernorm.weight"),
				config.getNormEpsilon()));
		branch.add(sequenceAttention(1, config.getMaxLength(), config.getHiddenSize(), config.getHeads(),
				weights.get(LAYER_ZERO + ".self_attn.to_qkv.weight"),
				weights.get(LAYER_ZERO + ".self_attn.o_proj.weight"),
				null, null, null, null, weights.get("encoder.rotary.inv_freq"), ProjectionFactory.dense(),
				NormalizationType.RMS, null, keyMask, config.getAttentionSoftcap()));
		branch.add(rmsnorm(blockShape, weights.get(LAYER_ZERO + ".post_self_attn_layernorm.weight"),
				config.getNormEpsilon()));
		return branch;
	}

	/**
	 * The feed-forward sub-layer as {@code T5GemmaEncoder.feedForwardBranch} assembled it in Java before
	 * the migration — RMS norm, the fused linear and gate projection, the linear half times GELU of the
	 * gate half, the down projection, RMS norm — with the epsilon of the first norm as an argument:
	 * {@code 1e-5} reproduces the assembly, whose first norm came from {@code gatedLinearFeedForward}
	 * with its default epsilon, and the configured epsilon gives the corrected sub-layer.
	 *
	 * @param config         the encoder configuration
	 * @param weights        the encoder weights
	 * @param preNormEpsilon the epsilon of the pre-normalization
	 * @return the sub-layer
	 */
	private Block referenceFeedForward(T5GemmaConfig config, Map<String, PackedCollection> weights,
									   double preNormEpsilon) {
		TraversalPolicy blockShape = blockShape(config);
		SequentialBlock branch = new SequentialBlock(blockShape);
		branch.add(rmsnorm(blockShape, weights.get(LAYER_ZERO + ".pre_feedforward_layernorm.weight"),
				preNormEpsilon));
		branch.add(dense(branch.getOutputShape(), weights.get(LAYER_ZERO + ".mlp.proj.weight"), null, false));
		List<Block> halves = branch.split(2, branch.getOutputShape().getDimensions() - 1, 0);
		branch.add(product(halves.get(1).andThen(gelu())));
		branch.add(dense(branch.getOutputShape(), weights.get(LAYER_ZERO + ".mlp.down_proj.weight"), null, false));
		branch.add(rmsnorm(blockShape, weights.get(LAYER_ZERO + ".post_feedforward_layernorm.weight"),
				config.getNormEpsilon()));
		return branch;
	}

	/**
	 * Builds a layer of the asset directly, without the encoder's binding code.
	 *
	 * @param layer      the layer name
	 * @param blockShape the layer's input shape
	 * @param arguments  the layer's arguments
	 * @return the layer
	 */
	private Block asset(String layer, TraversalPolicy blockShape, Map<String, Object> arguments) {
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(ASSET), layer, blockShape, arguments);
	}

	/**
	 * The arguments of the asset's layers for the first encoder layer: the geometry, the eight weights,
	 * the rotary frequencies, an all-ones value mask, the given prompt mask, the soft-cap and the
	 * epsilon. Each layer reads the arguments it declares.
	 *
	 * @param config  the encoder configuration
	 * @param weights the encoder weights
	 * @param keyMask the prompt mask, or {@code null} for a layer that reads none
	 * @return the arguments
	 */
	private Map<String, Object> assetArguments(T5GemmaConfig config, Map<String, PackedCollection> weights,
											   Producer<PackedCollection> keyMask) {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("batch", 1);
		arguments.put("seq_len", config.getMaxLength());
		arguments.put("heads", config.getHeads());
		arguments.put("head_dim", config.getHiddenSize() / config.getHeads());
		arguments.put("inner_dim", config.getIntermediateSize());
		for (String norm : new String[] {"pre_self_attn_layernorm", "post_self_attn_layernorm",
				"pre_feedforward_layernorm", "post_feedforward_layernorm"}) {
			arguments.put(norm, weights.get(LAYER_ZERO + "." + norm + ".weight"));
		}
		arguments.put("to_qkv", weights.get(LAYER_ZERO + ".self_attn.to_qkv.weight"));
		arguments.put("o_proj", weights.get(LAYER_ZERO + ".self_attn.o_proj.weight"));
		arguments.put("mlp_proj", weights.get(LAYER_ZERO + ".mlp.proj.weight"));
		arguments.put("down_proj", weights.get(LAYER_ZERO + ".mlp.down_proj.weight"));
		arguments.put("inv_freq", weights.get("encoder.rotary.inv_freq"));
		arguments.put("padding_mask", zeros(shape(1, config.getMaxLength())).add(1.0));
		arguments.put("key_mask", keyMask);
		arguments.put("softcap", config.getAttentionSoftcap());
		arguments.put("epsilon", config.getNormEpsilon());
		return arguments;
	}

	/**
	 * The small configuration with the attention soft-cap turned off.
	 *
	 * @return the configuration
	 */
	private T5GemmaConfig uncappedConfig() {
		T5GemmaConfig config = fixture.smallConfig(LENGTH);
		return new T5GemmaConfig(config.getHiddenSize(), config.getLayers(), config.getHeads(),
				config.getHeadDim(), config.getIntermediateSize(), config.getNormEpsilon(), 0.0,
				config.getRopeTheta(), config.getVocabularySize(), LENGTH);
	}

	/**
	 * The hidden state shape of the encoder, {@code [1, maxLength, hidden]}.
	 *
	 * @param config the encoder configuration
	 * @return the shape
	 */
	private TraversalPolicy blockShape(T5GemmaConfig config) {
		return shape(1, config.getMaxLength(), config.getHiddenSize());
	}

	/**
	 * Random hidden states of the encoder's shape with the given root mean square.
	 *
	 * @param config the encoder configuration
	 * @param seed   the random seed
	 * @param rms    the root mean square of the values
	 * @return the hidden states
	 */
	private PackedCollection hiddenStates(T5GemmaConfig config, long seed, double rms) {
		PackedCollection values = new PackedCollection(blockShape(config)).randnFill(new Random(seed));
		return cp(values).multiply(rms).into(values.traverseEach()).evaluate();
	}

	/**
	 * Compiles a block on its own, runs it once and returns a copy of its output that outlives the
	 * compiled model.
	 *
	 * @param block the block
	 * @param input the input
	 * @return the output
	 */
	private PackedCollection run(Block block, PackedCollection input) {
		Model model = new Model(block.getInputShape());
		model.add(block);
		try (CompiledModel compiled = model.compile(false)) {
			return compiled.forward(input).clone();
		}
	}

	/**
	 * Asserts that two framework computations of the same structure agree element by element within
	 * {@link #TOLERANCE}.
	 *
	 * @param label    the comparison, for the log and the failure message
	 * @param expected the reference output
	 * @param actual   the output under test
	 */
	private void assertClose(String label, PackedCollection expected, PackedCollection actual) {
		int size = expected.getShape().getTotalSize();
		assertEquals(label + ": element count", size, actual.getShape().getTotalSize());
		double deviation = largestDeviation(expected.reshape(shape(size)), actual.reshape(shape(size)));
		log(label + ": largestDeviation=" + deviation);
		assertTrue(label + ": largestDeviation=" + deviation + " exceeds " + TOLERANCE, deviation <= TOLERANCE);
	}

	/**
	 * Asserts that a framework computation of hidden states matches the host reference, one row per
	 * position, within {@link #HOST_TOLERANCE}.
	 *
	 * @param label    the comparison, for the log and the failure message
	 * @param expected the host reference, one row per position
	 * @param actual   the output under test, {@code [1, positions, hidden]}
	 */
	private void assertMatchesHost(String label, double[][] expected, PackedCollection actual) {
		TraversalPolicy rowsShape = shape(expected.length, expected[0].length);
		assertEquals(label + ": element count", rowsShape.getTotalSize(), actual.getShape().getTotalSize());
		double deviation = largestDeviation(rowsShape, position -> expected[position[0]][position[1]], actual);
		log(label + " vs host reference: largestDeviation=" + deviation);
		assertTrue(label + " vs host reference: largestDeviation=" + deviation + " exceeds " + HOST_TOLERANCE,
				deviation <= HOST_TOLERANCE);
	}
}
