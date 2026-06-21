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

import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.hardware.DefaultComputer;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.audio.ConditioningMode;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.ml.audio.DiffusionTransformerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Cross-compilation determinism reproduction / diagnostic harness (framework determinism issue,
 * NOT a Block-B2 memory-token defect).
 *
 * <p>This is information-gathering scaffolding ahead of an interactive framework-determinism
 * debugging session. It is intentionally <em>separate</em> from {@link LearnedTokensTest} (the B2
 * tests) and does not modify any B2 implementation. It exists to (1) reproduce the cross-compile
 * nondeterminism that the prior diagnosis isolated, (2) narrow it toward a minimal triggering graph,
 * and (3) emit XML profile artifacts (generated code + scheduling + timing) that the
 * {@code ar-profile-analyzer} consumes, so the divergence site can be located by diffing the two
 * compiles' generated code.</p>
 *
 * <h2>Established phenomenon (prior diagnosis, workstream memory {@code 1bd55507})</h2>
 * <ul>
 *   <li>Two <em>separately compiled</em>, structurally identical compute graphs (same weights, same
 *       input) produce <b>byte-identical</b> output when the transformer's internal sequence length is
 *       <b>even</b>, but <b>diverge</b> (~1e-3, amplified hugely under random adaLN gates) when it is
 *       <b>odd</b> (e.g. {@code seqLen=9 = audioSeqLen 8 + 1 prepended conditioning token}).</li>
 *   <li>The <em>same</em> compiled model run twice is deterministic (diff {@code 0.0}) — so this is
 *       <b>cross-compilation</b> nondeterminism, not runtime nondeterminism.</li>
 *   <li>Isolated softmax over an odd axis and isolated scaled-dot-product attention over an odd
 *       sequence length are each {@code 0.0} — the divergence is <b>emergent</b> from optimizing a
 *       larger graph at an odd sequence length, consistent with identity-hash-dependent operation
 *       grouping / scheduling in the optimizer / codegen ({@code compute/base}).</li>
 * </ul>
 *
 * <h2>What this harness asserts (and what it deliberately does not)</h2>
 * <p>The odd-sequence-length divergence is, by hypothesis, driven by object identity hashes that vary
 * from one compilation to the next; observing it is therefore probabilistic and must <b>not</b> be a
 * hard pass/fail assertion (that would be a flaky test). This harness instead hard-asserts only the
 * <b>reliable invariants</b> — a single compiled graph is deterministic across runs, and an even
 * sequence length is byte-identical across compiles — and <b>records</b> the odd-sequence-length
 * diffs as logged evidence plus emitted profile artifacts. When the framework determinism fix lands,
 * the recorded odd-sequence-length diffs become reliably {@code 0.0}; at that point this harness can
 * be relocated to the fix branch and the odd-sequence-length checks promoted to
 * {@code assertEquals(0.0, diff)} regression guards.</p>
 *
 * <h2>Artifacts</h2>
 * <p>Per-compile XML profiles are written under {@code AR_DETERMINISM_OUTPUT_DIR} (default
 * {@code results/determinism}, which is git-ignored by {@code **}{@code /results/}{@code **}). The
 * artifacts are large and must never be committed. Load a pair with
 * {@code mcp__ar-profile-analyzer__load_profile} and diff the generated code with
 * {@code mcp__ar-profile-analyzer__get_source} on matching node keys to locate where the two compiles
 * first differ.</p>
 *
 * @see LearnedTokensTest
 * @see DiffusionTransformer
 */
public class CompileDeterminismReproductionTest extends TestSuiteBase implements DiffusionTransformerFeatures {

	/** Batch dimension (scaled-dot-product attention currently asserts a batch size of 1). */
	private static final int BATCH = 1;
	/** Input/output audio channel count for the DiT-shaped cases. */
	private static final int IO = 2;
	/** Transformer embedding dimension. */
	private static final int DIM = 32;
	/** Number of self-attention heads. */
	private static final int HEADS = 2;
	/** Global conditioning vector dimension (drives the prepended conditioning token in PREPEND mode). */
	private static final int GLOBAL = 16;
	/** Audio sequence length (even); the PREPEND conditioning token lengthens this to an odd 9. */
	private static final int AUDIO = 8;
	/** Conditioning sequence length (cross-attention is disabled in these cases). */
	private static final int COND_SEQ = 4;

	/**
	 * Reproduces and narrows the cross-compile divergence, and emits profile artifacts for the
	 * analyzer. Hard assertions cover only the reliable invariants (see the class javadoc); the
	 * odd-sequence-length divergence is logged and dumped, not asserted, to avoid flakiness.
	 */
	@Test(timeout = 600000)
	public void crossCompileDeterminismDiagnostic() {
		String dir = outputDir();
		log("compile-determinism artifacts dir = " + new File(dir).getAbsolutePath());

		List<String> summary = new ArrayList<>();

		// Shared inputs reused by every DiT-shaped case (same input to every compile).
		PackedCollection ditInput = new PackedCollection(shape(BATCH, IO, AUDIO)).randnFill();
		PackedCollection ditTimestep = new PackedCollection(shape(BATCH, 1)).randnFill();
		PackedCollection ditGlobalCond = new PackedCollection(shape(BATCH, GLOBAL)).randnFill();

		// ---- Anchor: full DiffusionTransformer, PREPEND, odd internal seqLen = 9 (the known repro) ----
		Map<String, PackedCollection> prependWeights = ditWeightMap(false);
		DiffusionTransformer ditA = newPrepend(cloneWeights(prependWeights));
		DiffusionTransformer ditB = newPrepend(cloneWeights(prependWeights));
		DiffusionTransformer.enableProfile = true;
		PackedCollection outA = ditA.forward(ditInput, ditTimestep, null, ditGlobalCond);
		PackedCollection outARerun = ditA.forward(ditInput, ditTimestep, null, ditGlobalCond);
		PackedCollection outB = ditB.forward(ditInput, ditTimestep, null, ditGlobalCond);
		DiffusionTransformer.enableProfile = false;
		// Detach the DiT's profile listeners so the even-seqLen control compile below is not captured
		// into (and does not inflate the timing of) the anchor's profile.
		Hardware.getLocalHardware().assignProfile(null);

		double sameCompileDiff = maxAbsDiff("dit PREPEND same-compile rerun", outA, outARerun);
		double ditOddDiff = maxAbsDiff("dit PREPEND odd seqLen=9 cross-compile", outA, outB);
		summary.add(row("dit PREPEND odd seqLen=9 (full DiT, ANCHOR)", ditOddDiff));
		saveDitProfile(ditA, dir + "/dit_prepend_odd9_A.xml");
		saveDitProfile(ditB, dir + "/dit_prepend_odd9_B.xml");
		ditA.destroy();
		ditB.destroy();

		// ---- Even-seqLen control: full DiffusionTransformer, ADALN, seqLen stays 8 ----
		Map<String, PackedCollection> adaLNWeights = ditWeightMap(true);
		DiffusionTransformer adaA = newAdaLN(cloneWeights(adaLNWeights));
		DiffusionTransformer adaB = newAdaLN(cloneWeights(adaLNWeights));
		PackedCollection adaOutA = adaA.forward(ditInput, ditTimestep, null, ditGlobalCond);
		PackedCollection adaOutB = adaB.forward(ditInput, ditTimestep, null, ditGlobalCond);
		double ditEvenDiff = maxAbsDiff("dit ADALN even seqLen=8 cross-compile", adaOutA, adaOutB);
		summary.add(row("dit ADALN even seqLen=8 (full DiT, CONTROL)", ditEvenDiff));
		adaA.destroy();
		adaB.destroy();

		// ---- Hand-built narrowing ladder (shared weights/input across both compiles of each case) ----
		Map<String, PackedCollection> block = blockWeights();
		PackedCollection condToken = new PackedCollection(shape(DIM)).randnFill();
		PackedCollection projIn = new PackedCollection(shape(DIM, IO)).randnFill();
		PackedCollection projOut = new PackedCollection(shape(IO, DIM)).randnFill();
		PackedCollection inputChannelsFirst = new PackedCollection(shape(BATCH, IO, AUDIO)).randnFill();
		PackedCollection inputEven = new PackedCollection(shape(BATCH, AUDIO, DIM)).randnFill();
		PackedCollection inputOdd = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();

		// DiT structure minus the two residual 1x1 convolutions.
		runCase(summary, "hand DiT-no-conv odd seqLen=9",
				() -> ditNoConvModel(block, projIn, projOut, condToken),
				inputChannelsFirst, dir + "/hand_ditNoConv_odd9");
		// Just the prepend-concat (8 -> 9) feeding one transformer block, in embedding space.
		runCase(summary, "hand concat+block odd seqLen=9",
				() -> concatBlockModel(block, condToken),
				inputEven, dir + "/hand_concatBlock_odd9");
		// One transformer block at an odd seqLen reached directly (no concat).
		runCase(summary, "hand direct block odd seqLen=9",
				() -> directBlockModel(AUDIO + 1, block),
				inputOdd, dir + "/hand_directBlock_odd9");
		// Even-seqLen control for the direct block (profiles emitted as the negative structural
		// baseline: for an even seqLen the two compiles are expected to be byte-identical).
		runCase(summary, "hand direct block even seqLen=8 (control)",
				() -> directBlockModel(AUDIO, block),
				inputEven, dir + "/hand_directBlock_even8");
		// Block minus the feed-forward (self-attention sub-block only) at odd seqLen — the minimal
		// divergent trigger; emit its profiles for the cleanest possible generated-code diff.
		runCase(summary, "hand attn-only odd seqLen=9",
				() -> attnOnlyModel(AUDIO + 1, block),
				inputOdd, dir + "/hand_attnOnly_odd9");
		// Block minus the attention (feed-forward sub-block only) at odd seqLen.
		runCase(summary, "hand ffn-only odd seqLen=9",
				() -> ffnOnlyModel(AUDIO + 1, block),
				inputOdd, null);

		// Release the global profile listeners so profiling does not leak into other tests.
		Hardware.getLocalHardware().assignProfile(null);

		log("==== cross-compile determinism summary (maxAbsDiff of two separate compiles) ====");
		summary.forEach(this::log);
		log(ditOddDiff > 0.0
				? "REPRODUCED: cross-compile divergence at odd seqLen=9 (full DiT PREPEND), maxAbsDiff=" + ditOddDiff
				: "NOT REPRODUCED this run: full DiT PREPEND odd seqLen=9 produced byte-identical output");

		// Reliable invariants only (the odd-seqLen divergence is recorded above, not asserted).
		assertTrue("a single compiled graph must be deterministic when re-run (got maxAbsDiff="
				+ sameCompileDiff + ")", sameCompileDiff == 0.0);
		assertTrue("even seqLen must be byte-identical across compiles (ADALN seqLen=8, got maxAbsDiff="
				+ ditEvenDiff + ")", ditEvenDiff == 0.0);
	}

	/**
	 * Compiles the model produced by {@code builder} twice (two independent graph instances over the
	 * same shared weights), runs both on the same input, and returns the maximum absolute element-wise
	 * difference. When {@code profileBase} is non-null, the two compiles' XML profiles are written to
	 * {@code profileBase + "_A.xml"} and {@code profileBase + "_B.xml"}.
	 *
	 * @param label       label for logging
	 * @param builder     supplies a fresh {@link Model} instance per call (same weights, new graph objects)
	 * @param input       shared input fed to both compiled models
	 * @param profileBase artifact path prefix, or {@code null} to skip artifact emission
	 * @return the maximum absolute difference between the two compiles' outputs
	 */
	private double compileTwiceMaxAbsDiff(String label, Supplier<Model> builder,
										  PackedCollection input, String profileBase) {
		Model a = builder.get();
		Model b = builder.get();

		OperationProfileNode profileA = new OperationProfileNode(label + "_A");
		OperationProfileNode profileB = new OperationProfileNode(label + "_B");

		Hardware.getLocalHardware().assignProfile(profileA);
		CompiledModel compiledA = a.compile(false, profileA);
		Hardware.getLocalHardware().assignProfile(profileB);
		CompiledModel compiledB = b.compile(false, profileB);

		PackedCollection outputA = compiledA.forward(input);
		PackedCollection outputB = compiledB.forward(input);
		double diff = maxAbsDiff(label + " cross-compile", outputA, outputB);

		if (profileBase != null) {
			saveProfile(profileA, profileBase + "_A.xml");
			saveProfile(profileB, profileBase + "_B.xml");
		}

		// TODO(review): destroy ordering may be unsafe — compiledA/compiledB should be destroyed before a/b in case CompiledModel holds references to Model-allocated native buffers (see review-followup memory c578778a)
		a.destroy();
		b.destroy();
		compiledA.destroy();
		compiledB.destroy();
		return diff;
	}

	/**
	 * Runs a single narrowing case, recording the result (or any failure) into the summary so one
	 * broken case does not abort the whole diagnostic.
	 *
	 * @param summary     the running summary table
	 * @param label       case label
	 * @param builder     model builder for the case
	 * @param input       shared input
	 * @param profileBase artifact path prefix, or {@code null} to skip artifact emission
	 */
	private void runCase(List<String> summary, String label, Supplier<Model> builder,
						 PackedCollection input, String profileBase) {
		try {
			double diff = compileTwiceMaxAbsDiff(label, builder, input, profileBase);
			summary.add(row(label, diff));
		} catch (Exception | Error e) {
			summary.add(String.format("%-44s ERROR %s", label, e));
			warn("narrowing case '" + label + "' failed: " + e);
		}
	}

	// ---- Hand-built graph builders -------------------------------------------------------------

	/**
	 * One standard transformer block over an input that is already {@code [batch, seqLen, dim]}.
	 *
	 * @param seqLen the (possibly odd) sequence length
	 * @param w      shared block weights
	 * @return the model
	 */
	private Model directBlockModel(int seqLen, Map<String, PackedCollection> w) {
		Model model = new Model(shape(BATCH, seqLen, DIM));
		addStandardBlock(model.sequential(), seqLen, w);
		return model;
	}

	/**
	 * A prepend-concat lengthening the sequence from {@link #AUDIO} to {@code AUDIO + 1} (odd),
	 * feeding one standard transformer block, in embedding space.
	 *
	 * @param w         shared block weights
	 * @param condToken the prepended conditioning token, shape {@code [dim]}
	 * @return the model
	 */
	private Model concatBlockModel(Map<String, PackedCollection> w, PackedCollection condToken) {
		Model model = new Model(shape(BATCH, AUDIO, DIM));
		SequentialBlock main = model.sequential();
		main.add(layer("prepend",
				shape(BATCH, AUDIO, DIM), shape(BATCH, AUDIO + 1, DIM),
				in -> concat(1, cp(condToken).reshape(BATCH, 1, DIM), c(in))));
		addStandardBlock(main, AUDIO + 1, w);
		return model;
	}

	/**
	 * The full DiT structure minus the two residual 1x1 convolutions: channel/sequence reshape,
	 * project-in, prepend-concat ({@code 8 -> 9}), one transformer block, project-out, strip-subset,
	 * and the inverse reshape.
	 *
	 * @param w         shared block weights
	 * @param projIn    project-in weight, shape {@code [dim, ioChannels]}
	 * @param projOut   project-out weight, shape {@code [ioChannels, dim]}
	 * @param condToken the prepended conditioning token, shape {@code [dim]}
	 * @return the model
	 */
	private Model ditNoConvModel(Map<String, PackedCollection> w, PackedCollection projIn,
								 PackedCollection projOut, PackedCollection condToken) {
		Model model = new Model(shape(BATCH, IO, AUDIO));
		SequentialBlock main = model.sequential();

		main.reshape(BATCH, IO, AUDIO).enumerate(1, 2, 1).reshape(BATCH, AUDIO, IO);
		main.add(dense(projIn));
		main.add(layer("prepend",
				shape(BATCH, AUDIO, DIM), shape(BATCH, AUDIO + 1, DIM),
				in -> concat(1, cp(condToken).reshape(BATCH, 1, DIM), c(in))));
		addStandardBlock(main, AUDIO + 1, w);
		main.add(dense(projOut));
		main.reshape(BATCH, AUDIO + 1, IO).subset(shape(BATCH, AUDIO, IO), 0, 1, 0);
		main.reshape(BATCH, AUDIO, IO).enumerate(1, 2, 1).reshape(BATCH, IO, AUDIO);
		return model;
	}

	/**
	 * The self-attention sub-block only (pre-norm + self-attention inside a residual), matching how
	 * {@code transformerBlock} constructs its self-attention branch, at the given sequence length.
	 *
	 * @param seqLen the (possibly odd) sequence length
	 * @param w      shared block weights
	 * @return the model
	 */
	private Model attnOnlyModel(int seqLen, Map<String, PackedCollection> w) {
		Model model = new Model(shape(BATCH, seqLen, DIM));
		SequentialBlock attention = new SequentialBlock(shape(BATCH, seqLen, DIM));
		attention.add(norm(w.get("preNorm_g"), w.get("preNorm_b")));
		attention.add(selfAttention(BATCH, seqLen, DIM, HEADS, AttentionVariant.STANDARD,
				w.get("qkv"), w.get("wo"),
				w.get("qn"), w.get("qb"), w.get("kn"), w.get("kb"),
				w.get("invFreq"), null, ProjectionFactory.dense()));
		model.sequential().add(residual(attention));
		return model;
	}

	/**
	 * The feed-forward sub-block only (gated linear feed-forward inside a residual) at the given
	 * sequence length.
	 *
	 * @param seqLen the (possibly odd) sequence length
	 * @param w      shared block weights
	 * @return the model
	 */
	private Model ffnOnlyModel(int seqLen, Map<String, PackedCollection> w) {
		Model model = new Model(shape(BATCH, seqLen, DIM));
		model.sequential().add(residual(gatedLinearFeedForward(shape(BATCH, seqLen, DIM),
				w.get("ffn_g"), w.get("ffn_b"),
				w.get("w1"), w.get("w1b"), w.get("w2"), w.get("w2b"),
				ProjectionFactory.dense())));
		return model;
	}

	/**
	 * Appends one standard (non-cross-attending, unmodulated) transformer block, matching the DiT's
	 * own {@code transformerBlock} invocation.
	 *
	 * @param main   the sequential block to append to
	 * @param seqLen the sequence length carried through the block
	 * @param w      shared block weights
	 */
	private void addStandardBlock(SequentialBlock main, int seqLen, Map<String, PackedCollection> w) {
		main.add(transformerBlock(BATCH, DIM, seqLen, HEADS,
				false, 0, null,
				w.get("preNorm_g"), w.get("preNorm_b"),
				w.get("qkv"), w.get("wo"),
				w.get("qn"), w.get("qb"), w.get("kn"), w.get("kb"),
				w.get("invFreq"),
				null, null, null, null, null, null, null, null, null,
				w.get("ffn_g"), w.get("ffn_b"),
				w.get("w1"), w.get("w2"), w.get("w1b"), w.get("w2b"),
				null, ProjectionFactory.dense(),
				AttentionVariant.STANDARD, null, null));
	}

	// ---- Weights -------------------------------------------------------------------------------

	/**
	 * Builds the per-block weights shared by the hand-built cases (self-attention, RoPE, QK-norm, and
	 * the gated feed-forward), keyed by short names.
	 *
	 * @return the block weight map
	 */
	private Map<String, PackedCollection> blockWeights() {
		int dimHead = DIM / HEADS;
		int hidden = DIM * 4;
		Map<String, PackedCollection> w = new HashMap<>();
		put(w, "preNorm_g", DIM);
		put(w, "preNorm_b", DIM);
		put(w, "qkv", DIM * 3, DIM);
		put(w, "wo", DIM, DIM);
		put(w, "qn", dimHead);
		put(w, "qb", dimHead);
		put(w, "kn", dimHead);
		put(w, "kb", dimHead);
		put(w, "invFreq", dimHead / 4);
		put(w, "ffn_g", DIM);
		put(w, "ffn_b", DIM);
		put(w, "w1", 2 * hidden, DIM);
		put(w, "w1b", 2 * hidden);
		put(w, "w2", DIM, hidden);
		put(w, "w2b", DIM);
		return w;
	}

	/**
	 * Builds the complete weight set a {@link DiffusionTransformer} consumes for the synthetic
	 * configuration (no cross-attention, with global conditioning), optionally including the per-layer
	 * adaLN {@code to_scale_shift_gate} parameter.
	 *
	 * @param adaLN whether to include the per-layer adaLN modulation weight
	 * @return the DiT weight map
	 */
	private Map<String, PackedCollection> ditWeightMap(boolean adaLN) {
		int dimHead = DIM / HEADS;
		int hidden = DIM * 4;
		Map<String, PackedCollection> w = new HashMap<>();
		put(w, "model.model.timestep_features.weight", 128, 1);
		put(w, "model.model.to_timestep_embed.0.weight", DIM, 256);
		put(w, "model.model.to_timestep_embed.0.bias", DIM);
		put(w, "model.model.to_timestep_embed.2.weight", DIM, DIM);
		put(w, "model.model.to_timestep_embed.2.bias", DIM);
		put(w, "model.model.to_global_embed.0.weight", DIM, GLOBAL);
		put(w, "model.model.to_global_embed.2.weight", DIM, DIM);
		put(w, "model.model.preprocess_conv.weight", IO, IO);
		put(w, "model.model.postprocess_conv.weight", IO, IO);
		put(w, "model.model.transformer.project_in.weight", DIM, IO);
		put(w, "model.model.transformer.project_out.weight", IO, DIM);
		put(w, "model.model.transformer.rotary_pos_emb.inv_freq", dimHead / 4);

		String p = "model.model.transformer.layers.0";
		put(w, p + ".pre_norm.gamma", DIM);
		put(w, p + ".pre_norm.beta", DIM);
		put(w, p + ".self_attn.to_qkv.weight", DIM * 3, DIM);
		put(w, p + ".self_attn.to_out.weight", DIM, DIM);
		put(w, p + ".self_attn.q_norm.weight", dimHead);
		put(w, p + ".self_attn.q_norm.bias", dimHead);
		put(w, p + ".self_attn.k_norm.weight", dimHead);
		put(w, p + ".self_attn.k_norm.bias", dimHead);
		put(w, p + ".ff_norm.gamma", DIM);
		put(w, p + ".ff_norm.beta", DIM);
		put(w, p + ".ff.ff.0.proj.weight", 2 * hidden, DIM);
		put(w, p + ".ff.ff.0.proj.bias", 2 * hidden);
		put(w, p + ".ff.ff.2.weight", DIM, hidden);
		put(w, p + ".ff.ff.2.bias", DIM);
		if (adaLN) {
			put(w, p + ".to_scale_shift_gate.weight", AdaptiveLayerNormFeatures.MODULATION_COMPONENTS, DIM);
		}
		return w;
	}

	/**
	 * Adds a randomly-initialized weight of the given shape to the weight map.
	 *
	 * @param weights the weight map
	 * @param key     the weight key
	 * @param dims    the weight dimensions
	 */
	private void put(Map<String, PackedCollection> weights, String key, int... dims) {
		weights.put(key, new PackedCollection(shape(dims)).randnFill());
	}

	/**
	 * Deep-clones a weight map so two transformer instances share identical values but distinct
	 * backing collections.
	 *
	 * @param source the source weight map
	 * @return a clone of the weight map
	 */
	private Map<String, PackedCollection> cloneWeights(Map<String, PackedCollection> source) {
		Map<String, PackedCollection> clone = new HashMap<>();
		source.forEach((key, value) -> clone.put(key, value.clone()));
		return clone;
	}

	/**
	 * Builds a synthetic PREPEND-mode {@link DiffusionTransformer} (odd internal seqLen 9).
	 *
	 * @param weights the weight map
	 * @return the transformer
	 */
	private DiffusionTransformer newPrepend(Map<String, PackedCollection> weights) {
		return new DiffusionTransformer(IO, DIM, 1, HEADS, 1, 0, GLOBAL, "rf_denoiser",
				AUDIO, COND_SEQ, ConditioningMode.PREPEND, 0, new StateDictionary(weights), false);
	}

	/**
	 * Builds a synthetic ADALN-mode {@link DiffusionTransformer} (even internal seqLen 8).
	 *
	 * @param weights the weight map
	 * @return the transformer
	 */
	private DiffusionTransformer newAdaLN(Map<String, PackedCollection> weights) {
		return new DiffusionTransformer(IO, DIM, 1, HEADS, 1, 0, GLOBAL, "rf_denoiser",
				AUDIO, COND_SEQ, ConditioningMode.ADALN, 0, new StateDictionary(weights), false);
	}

	// ---- Output / comparison helpers -----------------------------------------------------------

	/**
	 * Computes the maximum absolute element-wise difference between two collections and logs the
	 * byte-identical element count.
	 *
	 * @param label label for logging
	 * @param a     the first collection
	 * @param b     the second collection
	 * @return the maximum absolute difference
	 */
	private double maxAbsDiff(String label, PackedCollection a, PackedCollection b) {
		double[] x = a.toArray();
		double[] y = b.toArray();
		if (x.length != y.length) {
			throw new AssertionError(label + " shape mismatch: " + a.getShape() + " vs " + b.getShape());
		}

		double max = 0.0;
		int identical = 0;
		for (int i = 0; i < x.length; i++) {
			double d = Math.abs(x[i] - y[i]);
			if (d > max) max = d;
			if (d == 0.0) identical++;
		}
		log(String.format("%s: %d/%d elements byte-identical, maxAbsDiff=%.3e", label, identical, x.length, max));
		return max;
	}

	/**
	 * Saves a DiT-owned profile (created internally when {@code enableProfile} is set) to XML.
	 *
	 * @param transformer the transformer whose profile to save
	 * @param path        the artifact path
	 */
	private void saveDitProfile(DiffusionTransformer transformer, String path) {
		OperationProfile profile = transformer.getProfile();
		if (profile instanceof OperationProfileNode) {
			saveProfile((OperationProfileNode) profile, path);
		} else {
			warn("no OperationProfileNode profile available for " + path + " (got " + profile + ")");
		}
	}

	/**
	 * Writes a profile to XML, creating parent directories and logging the absolute path. Failures
	 * are logged rather than thrown so artifact emission never aborts the diagnostic.
	 *
	 * @param profile the profile to save
	 * @param path    the artifact path
	 */
	private void saveProfile(OperationProfileNode profile, String path) {
		try {
			File file = new File(path);
			if (file.getParentFile() != null) {
				file.getParentFile().mkdirs();
			}
			profile.save(path);
			log("wrote profile artifact: " + file.getAbsolutePath());
		} catch (IOException e) {
			warn("could not save profile " + path + ": " + e.getMessage());
		}
	}

	/**
	 * Resolves the artifact output directory from {@code AR_DETERMINISM_OUTPUT_DIR}, defaulting to the
	 * git-ignored {@code results/determinism}.
	 *
	 * @return the output directory path
	 */
	private String outputDir() {
		String dir = System.getenv("AR_DETERMINISM_OUTPUT_DIR");
		if (dir == null || dir.isEmpty()) {
			dir = "results/determinism";
		}
		return dir;
	}

	/**
	 * Formats one summary row.
	 *
	 * @param label the case label
	 * @param diff  the measured maximum absolute difference
	 * @return the formatted row
	 */
	private String row(String label, double diff) {
		return String.format("%-44s maxAbsDiff=%.3e %s", label, diff, diff > 0.0 ? "(DIVERGENT)" : "(identical)");
	}

	// ---- Structural fingerprint probe (H7 warmup-state vs identity-hash ordering) ---------------

	/**
	 * Disambiguation probe: compiles the same minimal graph several times in one JVM and records each
	 * compile's structural fingerprint (operation-graph tree node count and the number of generated
	 * kernel sources). This separates two candidate mechanisms for the cross-compile structural
	 * divergence:
	 *
	 * <ul>
	 *   <li><b>Warmup / static-cache state</b> — if the first compile is cold and every subsequent
	 *       compile is warm, the fingerprints form a {@code [X, Y, Y, Y, ...]} step. The divergence is
	 *       then a deterministic first-vs-second-compile effect, not object-identity ordering.</li>
	 *   <li><b>Object-identity ordering</b> — if the fingerprints vary with no stable pattern, the
	 *       divergence is driven by identity-hash iteration order, which differs from one compile to the
	 *       next regardless of warmup.</li>
	 * </ul>
	 *
	 * <p>Unlike the numeric divergence, the structural fingerprint diverges even at an even sequence
	 * length (where the numeric output is byte-identical), so this probe is deterministic evidence
	 * rather than probabilistic.</p>
	 */
	@Test(timeout = 600000)
	public void compileOrderStructuralFingerprint() {
		Map<String, PackedCollection> block = blockWeights();
		PackedCollection inputOdd = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();
		PackedCollection inputEven = new PackedCollection(shape(BATCH, AUDIO, DIM)).randnFill();

		int repetitions = 5;
		log("==== compile-order structural fingerprint (same graph compiled N times in one JVM) ====");
		fingerprintSequence("direct block odd seqLen=9", repetitions,
				() -> directBlockModel(AUDIO + 1, block), inputOdd);
		fingerprintSequence("direct block even seqLen=8", repetitions,
				() -> directBlockModel(AUDIO, block), inputEven);
		fingerprintSequence("attn-only odd seqLen=9", repetitions,
				() -> attnOnlyModel(AUDIO + 1, block), inputOdd);

		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Tests hypothesis H7a: the cold/warm structural split is caused by memory-residence-dependent
	 * argument aggregation in {@code MemoryDataArgumentMap} (a host-resident weight is aggregated; once
	 * it becomes kernel/device-resident after the first compile, it is not — yielding a different kernel
	 * structure). Runs the same fingerprint sequence under three conditions:
	 *
	 * <ol>
	 *   <li><b>shared weights, aggregation on</b> — the baseline; expected to show the {@code [X,Y,Y,Y]}
	 *       cold/warm split (distinctFingerprints &gt; 1).</li>
	 *   <li><b>fresh weights per compile, aggregation on</b> — every compile sees host-resident weights,
	 *       so if residence drives the split this collapses to a single fingerprint.</li>
	 *   <li><b>shared weights, aggregation off</b> — removes the aggregation path entirely; if
	 *       aggregation is the mechanism this also collapses to a single fingerprint.</li>
	 * </ol>
	 *
	 * <p>If conditions 2 and 3 both collapse to one fingerprint while condition 1 does not, H7a is
	 * confirmed and the divergence is localized to residence-dependent argument aggregation.</p>
	 */
	@Test(timeout = 600000)
	public void aggregationResidenceProbe() {
		Map<String, PackedCollection> sharedBlock = blockWeights();
		PackedCollection inputEven = new PackedCollection(shape(BATCH, AUDIO, DIM)).randnFill();
		int repetitions = 4;

		log("==== H7a probe: residence-dependent argument aggregation as the cold/warm cause ====");
		fingerprintSequence("even8 sharedW aggON", repetitions,
				() -> directBlockModel(AUDIO, sharedBlock), inputEven);
		fingerprintSequence("even8 freshW aggON", repetitions,
				() -> directBlockModel(AUDIO, blockWeights()), inputEven);

		boolean previousAggregation = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;
		try {
			fingerprintSequence("even8 sharedW aggOFF", repetitions,
					() -> directBlockModel(AUDIO, sharedBlock), inputEven);
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = previousAggregation;
		}

		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Saves the cold (first) and warm (second) compile profiles of a single shape so the two can be
	 * diffed with the profile analyzer to see exactly which generated kernels change between the JVM's
	 * first compile and its subsequent compiles. Run this as the first test in a fresh JVM so the first
	 * compile is genuinely cold. An even sequence length isolates the structural difference from any
	 * numeric divergence (the {@code _A.xml} is the cold compile, {@code _B.xml} the warm one).
	 */
	@Test(timeout = 600000)
	public void saveColdWarmProfiles() {
		String dir = outputDir();
		Map<String, PackedCollection> sharedBlock = blockWeights();
		PackedCollection inputEven = new PackedCollection(shape(BATCH, AUDIO, DIM)).randnFill();
		double diff = compileTwiceMaxAbsDiff("coldwarm directBlock even8",
				() -> directBlockModel(AUDIO, sharedBlock), inputEven, dir + "/coldwarm_directBlock_even8");
		log("coldwarm directBlock even8 numericDiff=" + diff + " (A=cold, B=warm)");
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Tests hypothesis H8: the cold/warm structural split is caused by signature-keyed instruction-set
	 * reuse ({@link ScopeSettings#enableInstructionSetReuse} plus {@code DefaultComputer}'s
	 * signature-keyed {@code instructionsCache}). With reuse disabled from a cold JVM start, every
	 * compile uses a fresh per-operation instruction manager, so if reuse is the cause every compile
	 * should produce the same (fused) structure and the {@code [X,Y,Y,Y]} split should collapse to a
	 * single fingerprint.
	 *
	 * <p>Run this alone in a fresh JVM so the first compile is genuinely cold.</p>
	 */
	@Test(timeout = 600000)
	public void coldWarmReuseOff() {
		boolean previousReuse = ScopeSettings.enableInstructionSetReuse;
		ScopeSettings.enableInstructionSetReuse = false;
		try {
			Map<String, PackedCollection> block = blockWeights();
			PackedCollection inputEven = new PackedCollection(shape(BATCH, AUDIO, DIM)).randnFill();
			PackedCollection inputOdd = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();
			log("==== H8 probe: instruction-set reuse DISABLED (expect split to collapse) ====");
			fingerprintSequence("even8 reuseOFF", 4, () -> directBlockModel(AUDIO, block), inputEven);
			fingerprintSequence("odd9 reuseOFF", 4, () -> directBlockModel(AUDIO + 1, block), inputOdd);
		} finally {
			ScopeSettings.enableInstructionSetReuse = previousReuse;
		}
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Three-way numeric comparison to determine which compile is canonical. Compiles the same
	 * odd-sequence-length block on identical weights and input under three cache conditions and reports
	 * pairwise max-abs differences:
	 *
	 * <ul>
	 *   <li><b>R</b> — instruction-set reuse disabled (no shared cache populated): the no-reuse
	 *       reference.</li>
	 *   <li><b>A</b> — reuse enabled, first (cold) compile: populates the shared signature cache.</li>
	 *   <li><b>B</b> — reuse enabled, second (warm) compile: may reuse kernels cached by A.</li>
	 * </ul>
	 *
	 * <p>If {@code R == A != B}, the cold/fused compile matches the no-reuse reference and the warm
	 * compile is the one whose value changed (reuse altered the result). If {@code R == B != A} the
	 * opposite. This isolates whether reuse corrupts the value or merely re-associates a reduction.</p>
	 */
	@Test(timeout = 600000)
	public void reuseThreeWayComparison() {
		Map<String, PackedCollection> block = blockWeights();
		PackedCollection input = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();

		boolean previousReuse = ScopeSettings.enableInstructionSetReuse;
		ScopeSettings.enableInstructionSetReuse = false;
		double[] r;
		try {
			r = compileForwardToArray(() -> directBlockModel(AUDIO + 1, block), input);
		} finally {
			ScopeSettings.enableInstructionSetReuse = previousReuse;
		}
		double[] a = compileForwardToArray(() -> directBlockModel(AUDIO + 1, block), input);
		double[] b = compileForwardToArray(() -> directBlockModel(AUDIO + 1, block), input);

		log(String.format("reuse 3-way: R-vs-A=%.3e R-vs-B=%.3e A-vs-B=%.3e",
				maxAbsDiffArray(r, a), maxAbsDiffArray(r, b), maxAbsDiffArray(a, b)));
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Sanity check for the {@code AR_HARDWARE_OFF_HEAP_SIZE=0} configuration: compiles the minimal
	 * self-attention sub-block twice (default instruction-set reuse) on identical input and reports both
	 * the cross-compile difference AND the magnitude of the outputs, so that "consistent but correct"
	 * can be distinguished from "consistent but garbage" (e.g. an uninitialized {@code 2^30} sentinel).
	 * A healthy result has O(1) output magnitude and a cross-compile difference of {@code 0.0}.
	 */
	@Test(timeout = 600000)
	public void offHeapValueSanity() {
		Map<String, PackedCollection> block = blockWeights();
		PackedCollection inputOdd = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();
		double[] a = compileForwardToArray(() -> attnOnlyModel(AUDIO + 1, block), inputOdd);
		double[] b = compileForwardToArray(() -> attnOnlyModel(AUDIO + 1, block), inputOdd);
		log(String.format("offHeapValueSanity attnOnly odd9: maxAbsValue=%.4e crossCompileDiff=%.3e sample[0..3]=%.4f,%.4f,%.4f,%.4f",
				maxAbsValue(a), maxAbsDiffArray(a, b), a[0], a[1], a[2], a[3]));
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Returns the maximum absolute value in a host array (to distinguish sane O(1) outputs from
	 * uninitialized-memory garbage).
	 *
	 * @param x the array
	 * @return the maximum absolute value
	 */
	private double maxAbsValue(double[] x) {
		double max = 0.0;
		for (double v : x) {
			if (Math.abs(v) > max) max = Math.abs(v);
		}
		return max;
	}

	/**
	 * Captures a divergent pair of profiles with instruction-set reuse DISABLED, so every operation is
	 * compiled fresh in both compiles. The two compiles share the same weights, so memory residence
	 * flips between compile A (weights host-resident, aggregated) and compile B (weights device-resident,
	 * not aggregated); the op whose kernel source differs between {@code _A.xml} and {@code _B.xml} is
	 * the invalid kernel. Loops until the outputs differ, then saves both profiles for diffing.
	 */
	@Test(timeout = 600000)
	public void saveDivergentReuseOffProfiles() {
		String dir = outputDir();
		boolean previousReuse = ScopeSettings.enableInstructionSetReuse;
		ScopeSettings.enableInstructionSetReuse = false;
		try {
			for (int i = 0; i < 16; i++) {
				Map<String, PackedCollection> block = blockWeights();
				PackedCollection input = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();

				OperationProfileNode profileA = new OperationProfileNode("reuseOff_A");
				Hardware.getLocalHardware().assignProfile(profileA);
				Model a = directBlockModel(AUDIO + 1, block);
				CompiledModel compiledA = a.compile(false, profileA);
				double[] outA = compiledA.forward(input).toArray();

				OperationProfileNode profileB = new OperationProfileNode("reuseOff_B");
				Hardware.getLocalHardware().assignProfile(profileB);
				Model b = directBlockModel(AUDIO + 1, block);
				CompiledModel compiledB = b.compile(false, profileB);
				double[] outB = compiledB.forward(input).toArray();

				double diff = maxAbsDiffArray(outA, outB);
				log("attempt " + i + " reuseOff directBlock odd9 diff=" + String.format("%.3e", diff));
				if (diff > 1e-5) {
					saveProfile(profileA, dir + "/reuseOff_divergent_A.xml");
					saveProfile(profileB, dir + "/reuseOff_divergent_B.xml");
					log("saved divergent reuse-off pair (diff=" + diff + ", A=host/cold, B=device/warm)");
					compiledA.destroy();
					compiledB.destroy();
					a.destroy();
					b.destroy();
					break;
				}
				compiledA.destroy();
				compiledB.destroy();
				a.destroy();
				b.destroy();
			}
		} finally {
			ScopeSettings.enableInstructionSetReuse = previousReuse;
		}
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Captures a genuinely divergent cold-vs-warm profile pair for the minimal self-attention sub-block
	 * at odd seqLen. Because the divergence is probabilistic per random weights, this clears the
	 * instruction-set reuse cache and compiles the same graph twice (A cold, B warm) repeatedly until
	 * the two outputs differ, then saves both profiles so the generated reduction kernels can be diffed
	 * with the profile analyzer. {@code _A.xml} is the cold compile, {@code _B.xml} the warm one.
	 */
	@Test(timeout = 600000)
	public void saveDivergentColdWarmProfiles() {
		String dir = outputDir();
		DefaultComputer computer = Hardware.getLocalHardware().getComputer();

		int attempts = 12;
		for (int i = 0; i < attempts; i++) {
			Map<String, PackedCollection> block = blockWeights();
			PackedCollection inputOdd = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();

			computer.clearInstructionsCache();
			OperationProfileNode profileA = new OperationProfileNode("attnOnly_odd9_A");
			Hardware.getLocalHardware().assignProfile(profileA);
			Model a = attnOnlyModel(AUDIO + 1, block);
			CompiledModel compiledA = a.compile(false, profileA);
			double[] outA = compiledA.forward(inputOdd).toArray();

			OperationProfileNode profileB = new OperationProfileNode("attnOnly_odd9_B");
			Hardware.getLocalHardware().assignProfile(profileB);
			Model b = attnOnlyModel(AUDIO + 1, block);
			CompiledModel compiledB = b.compile(false, profileB);
			double[] outB = compiledB.forward(inputOdd).toArray();

			double diff = maxAbsDiffArray(outA, outB);
			log("attempt " + i + " cold-vs-warm attnOnly odd9 diff=" + String.format("%.3e", diff));
			if (diff > 1e-5) {
				saveProfile(profileA, dir + "/divergent_attnOnly_odd9_A.xml");
				saveProfile(profileB, dir + "/divergent_attnOnly_odd9_B.xml");
				log("saved divergent pair (diff=" + diff + ", A=cold, B=warm)");
				compiledA.destroy();
				compiledB.destroy();
				a.destroy();
				b.destroy();
				break;
			}
			compiledA.destroy();
			compiledB.destroy();
			a.destroy();
			b.destroy();
		}
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Names the colliding operations: enables {@link DefaultComputer#enableReuseCollisionLog} and
	 * compiles the minimal divergent graph (self-attention sub-block at odd seqLen) twice, so the
	 * instruction-set reuse cache logs every hit where the cached computation's description differs from
	 * the requesting one. Each {@code reuseSignatureCollision} line identifies a specific operation that
	 * is being reused for a non-equivalent computation because the signature is too coarse.
	 */
	@Test(timeout = 600000)
	public void reuseCollisionDiagnostic() {
		Map<String, PackedCollection> block = blockWeights();
		PackedCollection inputOdd = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();

		boolean previous = DefaultComputer.enableReuseCollisionLog;
		DefaultComputer.enableReuseCollisionLog = true;
		try {
			log("==== reuse signature-collision diagnostic (attn-only odd seqLen=9, two compiles) ====");
			for (int i = 0; i < 2; i++) {
				Model model = attnOnlyModel(AUDIO + 1, block);
				CompiledModel compiled = model.compile(false, null);
				compiled.forward(inputOdd);
				compiled.destroy();
				model.destroy();
			}
		} finally {
			DefaultComputer.enableReuseCollisionLog = previous;
		}
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Confounder-free value sequence: computes a no-reuse reference, then compiles the same graph
	 * several times with reuse enabled while keeping every compiled model alive (so no destroy can evict
	 * cache entries), reporting each compile's value difference from the reference. This shows exactly
	 * which compiles match the canonical (no-reuse) result. If the pattern is {@code [diff, 0, 0, 0]} the
	 * only divergent compile is the first (cold) one and every warm compile is canonical.
	 */
	@Test(timeout = 600000)
	public void valueSequenceVsReference() {
		Map<String, PackedCollection> block = blockWeights();
		PackedCollection input = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();

		boolean previousReuse = ScopeSettings.enableInstructionSetReuse;
		ScopeSettings.enableInstructionSetReuse = false;
		double[] reference;
		try {
			reference = compileForwardToArray(() -> directBlockModel(AUDIO + 1, block), input);
		} finally {
			ScopeSettings.enableInstructionSetReuse = previousReuse;
		}

		int compiles = 4;
		List<Model> models = new ArrayList<>();
		List<CompiledModel> compiled = new ArrayList<>();
		List<String> diffs = new ArrayList<>();
		for (int i = 0; i < compiles; i++) {
			Model model = directBlockModel(AUDIO + 1, block);
			CompiledModel cm = model.compile(false, null);
			double[] out = cm.forward(input).toArray();
			models.add(model);
			compiled.add(cm);
			diffs.add(String.format("%.3e", maxAbsDiffArray(reference, out)));
		}
		log("value sequence vs no-reuse reference (all models kept alive): " + diffs);
		compiled.forEach(CompiledModel::destroy);
		models.forEach(Model::destroy);
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Compiles the model produced by {@code builder} once and returns its forward output as a host
	 * array. The model and compiled model are destroyed after the host copy is taken.
	 *
	 * @param builder supplies a fresh model
	 * @param input   the input to run
	 * @return the forward output copied to a host array
	 */
	private double[] compileForwardToArray(Supplier<Model> builder, PackedCollection input) {
		Model model = builder.get();
		CompiledModel compiled = model.compile(false, null);
		double[] out = compiled.forward(input).toArray();
		compiled.destroy();
		model.destroy();
		return out;
	}

	/**
	 * Returns the maximum absolute element-wise difference between two host arrays.
	 *
	 * @param x the first array
	 * @param y the second array
	 * @return the maximum absolute difference
	 */
	private double maxAbsDiffArray(double[] x, double[] y) {
		double max = 0.0;
		for (int i = 0; i < x.length; i++) {
			double d = Math.abs(x[i] - y[i]);
			if (d > max) max = d;
		}
		return max;
	}

	/**
	 * Numeric confirmation of H8: compiles the odd-sequence-length block twice with instruction-set
	 * reuse enabled (the default — compile A cold/fused, compile B warm/decomposed, expected to diverge)
	 * and again with reuse disabled (both compiles fused, expected to be byte-identical). Demonstrates
	 * that the cross-compile numeric divergence is caused by reuse, not by the model graph. Run alone in
	 * a fresh JVM so the reuse-enabled compile A is genuinely cold.
	 */
	@Test(timeout = 600000)
	public void numericDeterminismReuseOff() {
		Map<String, PackedCollection> block = blockWeights();
		PackedCollection inputOdd = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();
		double diffReuseOn = compileTwiceMaxAbsDiff("odd9 reuseON",
				() -> directBlockModel(AUDIO + 1, block), inputOdd, null);
		log("odd9 reuseON numericDiff=" + diffReuseOn + " (A=cold, B=warm)");

		boolean previousReuse = ScopeSettings.enableInstructionSetReuse;
		ScopeSettings.enableInstructionSetReuse = false;
		double diffReuseOff;
		try {
			Map<String, PackedCollection> freshBlock = blockWeights();
			PackedCollection freshInputOdd = new PackedCollection(shape(BATCH, AUDIO + 1, DIM)).randnFill();
			diffReuseOff = compileTwiceMaxAbsDiff("odd9 reuseOFF",
					() -> directBlockModel(AUDIO + 1, freshBlock), freshInputOdd, null);
		} finally {
			ScopeSettings.enableInstructionSetReuse = previousReuse;
		}
		log("odd9 reuseOFF numericDiff=" + diffReuseOff);
		log(String.format("H8 numeric: reuseON diff=%.3e, reuseOFF diff=%.3e", diffReuseOn, diffReuseOff));
		Hardware.getLocalHardware().assignProfile(null);
	}

	/**
	 * Compiles the model produced by {@code builder} {@code repetitions} times, running each compile on
	 * {@code input}, and logs the per-compile structural fingerprint plus the number of distinct
	 * fingerprints observed across the sequence.
	 *
	 * @param label       label for logging
	 * @param repetitions number of independent compiles
	 * @param builder     supplies a fresh model per compile (same weights, new graph objects)
	 * @param input       shared input fed to every compile
	 */
	private void fingerprintSequence(String label, int repetitions, Supplier<Model> builder,
									 PackedCollection input) {
		List<String> fingerprints = new ArrayList<>();
		for (int i = 0; i < repetitions; i++) {
			Model model = builder.get();
			OperationProfileNode profile = new OperationProfileNode(label + "_" + i);
			Hardware.getLocalHardware().assignProfile(profile);
			CompiledModel compiled = model.compile(false, profile);
			compiled.forward(input);
			int treeNodes = treeNodeCount(profile);
			int kernelSources = totalKernelSources(profile);
			fingerprints.add(treeNodes + "/" + kernelSources);
			log(String.format("%-28s compile[%d] treeNodes=%d kernelSources=%d",
					label, i, treeNodes, kernelSources));
			compiled.destroy();
			model.destroy();
		}
		log(String.format("%-28s sequence=%s distinctFingerprints=%d",
				label, fingerprints, fingerprints.stream().distinct().count()));
	}

	/**
	 * Counts the nodes in an operation-profile tree, inclusive of the root.
	 *
	 * @param node the subtree root
	 * @return the total node count
	 */
	private int treeNodeCount(OperationProfileNode node) {
		int total = 1;
		for (OperationProfileNode child : node.getChildren()) {
			total += treeNodeCount(child);
		}
		return total;
	}

	/**
	 * Counts the total number of generated kernel sources recorded across an operation-profile tree.
	 * A small count indicates the reductions were fused into compact kernels; a large count indicates
	 * they were emitted as a swarm of separate kernels.
	 *
	 * @param node the profile root
	 * @return the total number of generated kernel sources
	 */
	private int totalKernelSources(OperationProfileNode node) {
		int total = 0;
		if (node.getOperationSources() != null) {
			total += node.getOperationSources().values().stream().mapToInt(List::size).sum();
		}
		for (OperationProfileNode child : node.getChildren()) {
			total += totalKernelSources(child);
		}
		return total;
	}
}
