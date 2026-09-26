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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.SAMEResamplingTestBase;
import org.almostrealism.ml.ReferenceActivations;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage-wise numerical parity of {@link T5GemmaEncoder} against the released base encoder over the
 * fixed prompt of {@code dump_t5gemma_reference.py}: the scaled token embedding, the attention
 * branch and the feed-forward branch of layer zero, each fed the reference activation of the stage
 * before it so a discrepancy localizes to one stage, and the first layer as a whole.
 *
 * <p>Gated like {@link T5GemmaEncoderParityTest}: the weights come from
 * {@code AR_T5GEMMA_WEIGHTS} and the references from {@code AR_T5GEMMA_REFERENCES} (system
 * property first, then environment variable); the test logs a skip and returns when either is
 * absent. Sub-stage references that the dump does not hold are reported as absent rather than
 * failing.</p>
 */
public class T5GemmaEncoderStageParityTest extends SAMEResamplingTestBase {

	/** Candidate locations for the extracted weight directory (first existing wins). */
	private static final String[] WEIGHT_DIRS = {
			System.getProperty("AR_T5GEMMA_WEIGHTS", System.getenv("AR_T5GEMMA_WEIGHTS")),
			"/workspace/t5gemma-weights"
	};

	/** Candidate locations for the reference directory (first existing wins). */
	private static final String[] REFERENCE_DIRS = {
			System.getProperty("AR_T5GEMMA_REFERENCES", System.getenv("AR_T5GEMMA_REFERENCES")),
			"target/test-classes/t5gemma-references",
			"engine/ml/target/test-classes/t5gemma-references"
	};

	/** Permitted largest absolute error relative to the largest reference magnitude of a stage. */
	private static final double RELATIVE_TOLERANCE = 2e-2;

	/**
	 * Every stage of the first layer reproduces its reference within a small fraction of the
	 * reference magnitude. All stages are reported before any is asserted.
	 *
	 * @throws IOException if a reference file cannot be read
	 */
	@Test(timeout = 900000)
	public void layerZeroStagesMatchReference() throws IOException {
		File weightDir = firstExisting(WEIGHT_DIRS, "weights");
		File refDir = firstExisting(REFERENCE_DIRS, "t5_embeddings");
		if (weightDir == null || refDir == null) {
			log("skipping T5Gemma stage parity; gated inputs absent (weights=" + weightDir + ", refs=" + refDir + ")");
			return;
		}

		T5GemmaConfig config = T5GemmaConfig.baseUl2();
		int length = config.getMaxLength();
		int hidden = config.getHiddenSize();
		TraversalPolicy blockShape = shape(1, length, hidden);

		float[] ids = loadFlat(refDir, "t5_input_ids");
		float[] mask = loadFlat(refDir, "t5_attention_mask");
		int valid = 0;
		while (valid < mask.length && mask[valid] > 0.5) {
			valid++;
		}
		long[] prompt = new long[valid];
		for (int i = 0; i < valid; i++) {
			prompt[i] = Math.round(ids[i]);
		}

		T5GemmaEncoder encoder = new T5GemmaEncoder(config, new StateDictionary(weightDir.getPath()));
		PackedCollection tokenIds = encoder.loadPrompt(prompt);
		String prefix = "encoder.layers.0";
		List<String> failures = new ArrayList<>();

		PackedCollection embeddings = evalBlock(
				encoder.tokenEmbedding(encoder.weight("encoder.embed_tokens.weight", config.getVocabularySize(), hidden)),
				tokenIds);
		check(failures, "embeddings", embeddings, refDir, "t5_embeddings");
		reportPositions("embeddings", embeddings, loadFlat(refDir, "t5_embeddings"), prompt, hidden);

		PackedCollection refEmbeddings = loadShaped(refDir, "t5_embeddings", 1, length, hidden);
		Block attention = encoder.attentionBranch(blockShape, prefix, encoder.weight("encoder.rotary.inv_freq", config.getHeadDim() / 2));
		PackedCollection attentionOut = evalBlock(attention, refEmbeddings);
		check(failures, "attentionBranch", attentionOut, refDir, "t5_l0_post_attn_norm");

		PackedCollection afterAttention = cp(refEmbeddings).add(cp(attentionOut)).evaluate();
		check(failures, "afterAttention", afterAttention, refDir, "t5_l0_after_attn");

		PackedCollection ffInput = references(refDir).contains("t5_l0_after_attn") ?
				loadShaped(refDir, "t5_l0_after_attn", 1, length, hidden) : afterAttention;
		Block feedForward = encoder.feedForwardBranch(blockShape, prefix);
		PackedCollection feedForwardOut = evalBlock(feedForward, ffInput);
		check(failures, "feedForwardBranch", feedForwardOut, refDir, "t5_l0_post_ff_norm");

		PackedCollection layerOut = cp(ffInput).add(cp(feedForwardOut)).evaluate();
		check(failures, "layer0", layerOut, refDir, "t5_hidden_layer0");

		encoder.destroy();
		assertTrue(String.join("; ", failures), failures.isEmpty());
	}

	/**
	 * Logs the largest error of every prompt position of a stage, with the token id at that
	 * position, so an error confined to particular tokens shows up as such.
	 *
	 * @param stage     the stage label
	 * @param actual    the computed activation, {@code [1, length, hidden]}
	 * @param reference the flat reference values
	 * @param prompt    the prompt's token ids
	 * @param hidden    the hidden width
	 */
	private void reportPositions(String stage, PackedCollection actual, float[] reference, long[] prompt, int hidden) {
		double[] values = actual.toArray(0, actual.getShape().getTotalSize());
		for (int p = 0; p < prompt.length; p++) {
			double maxAbs = 0.0;
			for (int f = 0; f < hidden; f++) {
				maxAbs = Math.max(maxAbs, Math.abs(values[p * hidden + f] - reference[p * hidden + f]));
			}

			log(stage + " position=" + p + " token=" + prompt[p] + " maxAbs=" + maxAbs);
		}
	}

	/**
	 * Reports one stage against its reference when the reference exists, recording a failure when
	 * the largest error exceeds the tolerance.
	 *
	 * @param failures the failure messages collected so far
	 * @param stage    the stage label
	 * @param actual   the computed activation
	 * @param refDir   the reference directory
	 * @param name     the reference key
	 * @throws IOException if the references cannot be read
	 */
	private void check(List<String> failures, String stage, PackedCollection actual,
					   File refDir, String name) throws IOException {
		ReferenceActivations references = references(refDir);
		if (!references.contains(name)) {
			log(stage + ": reference " + name + " absent");
			return;
		}

		float[] reference = references.load(name);
		double[] stats = diffStats(actual, reference);
		report(stage, actual, reference);
		double tolerance = RELATIVE_TOLERANCE * stats[3];
		if (!(stats[0] <= tolerance)) {
			failures.add(stage + " maxAbs=" + stats[0] + " > " + tolerance);
		}
	}
}
