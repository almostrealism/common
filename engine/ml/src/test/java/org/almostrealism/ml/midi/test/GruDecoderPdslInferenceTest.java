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

package org.almostrealism.ml.midi.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * End-to-end PDSL inference test for the GRU decoder.
 *
 * <p>This test demonstrates that ALL GRU computation is defined in
 * {@code /pdsl/midi/gru_decoder.pdsl} and that inference can be driven
 * purely by loading a PDSL file and binding weights from a
 * {@link StateDictionary}. No Java computation classes are involved.</p>
 *
 * <h2>PDSL Coverage</h2>
 * <ul>
 *   <li>{@code summary_proj} — projects transformer hidden → decoder hidden</li>
 *   <li>{@code gru_r_gate} — reset gate: sigmoid(W_ir@x + W_hr@h)</li>
 *   <li>{@code gru_z_gate} — update gate: sigmoid(W_iz@x + W_hz@h)</li>
 *   <li>{@code gru_n_gate} — candidate gate: tanh(W_in@x + r*(W_hn@h))</li>
 *   <li>{@code gru_h_new}  — hidden update: lerp(n, z, h)</li>
 *   <li>{@code lm_head}    — projects decoder hidden → vocab logits</li>
 * </ul>
 *
 * <h2>Why the 7-step loop lives in Java (not PDSL)</h2>
 * <p>The autoregressive decode loop cannot be expressed as a static computation
 * graph because each step's input embedding is selected by taking the argmax of
 * the previous step's logits — a data-dependent branch. Expressing this would
 * require dynamic loop support and conditional embedding lookup, neither of which
 * is currently in the PDSL. The loop is therefore orchestrated here in test code
 * using compiled PDSL blocks; all neural-network math remains in the PDSL file.</p>
 *
 * <h2>Inputs</h2>
 * <ol>
 *   <li>{@code /pdsl/midi/gru_decoder.pdsl} — PDSL file defining all GRU computation</li>
 *   <li>{@code /Users/Shared/models/moonbeam-weights-protobuf} — protobuf weight directory
 *       (test is skipped if not present)</li>
 * </ol>
 */
public class GruDecoderPdslInferenceTest extends TestSuiteBase {

	private static final String WEIGHTS_DIR = "/Users/Shared/models/moonbeam-weights-protobuf";
	private static final String PDSL_RESOURCE = "/pdsl/midi/gru_decoder.pdsl";

	// -----------------------------------------------------------------------
	//  Test 1: Synthetic weights (always runs, validates PDSL loading + flow)
	// -----------------------------------------------------------------------

	/**
	 * Load {@code gru_decoder.pdsl}, build all GRU blocks with synthetic random
	 * weights, and run a full 7-step decode. Validates that the PDSL pipeline
	 * produces {@link GRUDecoder#TOKENS_PER_NOTE} output tokens.
	 */
	@Test
	public void testGruDecoderPdslInferenceSynthetic() throws IOException {
		int decoderHidden = 8;   // small for test speed
		int vocabSize = 16;
		int numLayers = 2;
		int hiddenSize = 8;      // transformer hidden (same as decoderHidden for simplicity)
		Random rng = new Random(42);

		// Load PDSL program
		PdslNode.Program program = loadPdslProgram();

		// Summary projection: (hiddenSize) -> (decoderHidden)
		PackedCollection summaryW = randomCollection(rng, decoderHidden, hiddenSize);
		PackedCollection summaryB = randomCollection(rng, decoderHidden);
		CompiledModel summaryModel = compilePdslLayer(program, "summary_proj",
				new TraversalPolicy(hiddenSize),
				mapOf("w", summaryW, "b", summaryB));

		// LM head: (decoderHidden) -> (vocabSize)
		PackedCollection lmW = randomCollection(rng, vocabSize, decoderHidden);
		PackedCollection lmB = randomCollection(rng, vocabSize);
		CompiledModel lmHeadModel = compilePdslLayer(program, "lm_head",
				new TraversalPolicy(decoderHidden),
				mapOf("w", lmW, "b", lmB));

		// GRU gate models per layer
		CompiledModel[] rGate = new CompiledModel[numLayers];
		CompiledModel[] zGate = new CompiledModel[numLayers];
		CompiledModel[] nGate = new CompiledModel[numLayers];
		CompiledModel[] hNew  = new CompiledModel[numLayers];

		for (int l = 0; l < numLayers; l++) {
			// weightIh = (3*decoderHidden, decoderHidden), biasIh = (3*decoderHidden)
			PackedCollection wIr = randomCollection(rng, decoderHidden, decoderHidden);
			PackedCollection bIr = randomCollection(rng, decoderHidden);
			PackedCollection wHr = randomCollection(rng, decoderHidden, decoderHidden);
			PackedCollection bHr = randomCollection(rng, decoderHidden);

			PackedCollection wIz = randomCollection(rng, decoderHidden, decoderHidden);
			PackedCollection bIz = randomCollection(rng, decoderHidden);
			PackedCollection wHz = randomCollection(rng, decoderHidden, decoderHidden);
			PackedCollection bHz = randomCollection(rng, decoderHidden);

			PackedCollection wIn = randomCollection(rng, decoderHidden, decoderHidden);
			PackedCollection bIn = randomCollection(rng, decoderHidden);
			PackedCollection wHn = randomCollection(rng, decoderHidden, decoderHidden);
			PackedCollection bHn = randomCollection(rng, decoderHidden);

			// r and z gates: input [x|h] of shape (2*decoderHidden)
			rGate[l] = compilePdslLayer(program, "gru_r_gate",
					new TraversalPolicy(2 * decoderHidden),
					mapOf("w_ir", wIr, "b_ir", bIr, "w_hr", wHr, "b_hr", bHr,
							"input_size", decoderHidden, "hidden_size", decoderHidden));
			zGate[l] = compilePdslLayer(program, "gru_z_gate",
					new TraversalPolicy(2 * decoderHidden),
					mapOf("w_iz", wIz, "b_iz", bIz, "w_hz", wHz, "b_hz", bHz,
							"input_size", decoderHidden, "hidden_size", decoderHidden));

			// n gate: input [x|h|r] of shape (3*decoderHidden)
			nGate[l] = compilePdslLayer(program, "gru_n_gate",
					new TraversalPolicy(3 * decoderHidden),
					mapOf("w_in", wIn, "b_in", bIn, "w_hn", wHn, "b_hn", bHn,
							"input_size", decoderHidden, "hidden_size", decoderHidden));

			// h_new: input [n|z|h] of shape (3*decoderHidden)
			hNew[l] = compilePdslLayer(program, "gru_h_new",
					new TraversalPolicy(3 * decoderHidden),
					mapOf("hidden_size", decoderHidden));
		}

		// Decoder embedding table: (vocabSize, decoderHidden)
		PackedCollection embedTable = randomCollection(rng, vocabSize, decoderHidden);

		// Synthetic transformer hidden state
		PackedCollection transformerHidden = randomCollection(rng, hiddenSize);

		// Run inference
		int[] outputTokens = runGruDecode(
				transformerHidden, summaryModel, rGate, zGate, nGate, hNew,
				lmHeadModel, embedTable, decoderHidden, vocabSize,
				GRUDecoder.TOKENS_PER_NOTE);

		Assert.assertEquals("Should produce 7 output tokens",
				GRUDecoder.TOKENS_PER_NOTE, outputTokens.length);
		for (int i = 0; i < outputTokens.length; i++) {
			Assert.assertTrue("Token " + i + " must be >= 0", outputTokens[i] >= 0);
			Assert.assertTrue("Token " + i + " must be < vocabSize",
					outputTokens[i] < vocabSize);
		}

		System.out.println("[GruDecoderPdslInferenceTest] synthetic decode: "
				+ Arrays.toString(outputTokens));
	}

	// -----------------------------------------------------------------------
	//  Test 2: Real weights (skipped if /Users/Shared/... not present)
	// -----------------------------------------------------------------------

	/**
	 * Load {@code gru_decoder.pdsl}, bind Moonbeam protobuf weights via
	 * {@link StateDictionary}, and run a full 7-step GRU decode.
	 *
	 * <p>Skipped when the weights directory is not present.</p>
	 *
	 * <p><strong>Inputs:</strong></p>
	 * <ol>
	 *   <li>{@value PDSL_RESOURCE} — all GRU computation in PDSL</li>
	 *   <li>{@value WEIGHTS_DIR} — Moonbeam protobuf weights</li>
	 * </ol>
	 */
	@Test
	@TestDepth(2)
	public void testGruDecoderPdslInference() throws IOException {
		Assume.assumeTrue("Moonbeam weights not found at " + WEIGHTS_DIR,
				new File(WEIGHTS_DIR).isDirectory());

		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();
		StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);

		// Load PDSL program
		PdslNode.Program program = loadPdslProgram();

		// Summary projection: transformer hidden → decoder hidden
		PackedCollection summaryW = stateDict.get("summary_projection.weight");
		PackedCollection summaryB = stateDict.get("summary_projection.bias");
		CompiledModel summaryModel = compilePdslLayer(program, "summary_proj",
				new TraversalPolicy(config.hiddenSize),
				mapOf("w", summaryW, "b", summaryB));

		// LM head: decoder hidden → vocab logits
		PackedCollection lmW = stateDict.get("lm_head.weight");
		PackedCollection lmB = stateDict.get("lm_head.bias");
		CompiledModel lmHeadModel = compilePdslLayer(program, "lm_head",
				new TraversalPolicy(config.decoderHiddenSize),
				mapOf("w", lmW, "b", lmB));

		// GRU gate models per layer
		int numLayers = config.decoderLayers;
		CompiledModel[] rGate = new CompiledModel[numLayers];
		CompiledModel[] zGate = new CompiledModel[numLayers];
		CompiledModel[] nGate = new CompiledModel[numLayers];
		CompiledModel[] hNew  = new CompiledModel[numLayers];

		for (int l = 0; l < numLayers; l++) {
			// Per-layer stacked weight tensors from StateDictionary
			PackedCollection weightIh = stateDict.get(
					String.format("decoder.weight_ih_l%d", l));
			PackedCollection weightHh = stateDict.get(
					String.format("decoder.weight_hh_l%d", l));
			PackedCollection biasIh = stateDict.get(
					String.format("decoder.bias_ih_l%d", l));
			PackedCollection biasHh = stateDict.get(
					String.format("decoder.bias_hh_l%d", l));

			int dh = config.decoderHiddenSize;
			int rowStride = dh * dh;

			// Zero-copy views into stacked weight tensors (range = no copy)
			PackedCollection wIr = weightIh.range(new TraversalPolicy(dh, dh), 0);
			PackedCollection wIz = weightIh.range(new TraversalPolicy(dh, dh), rowStride);
			PackedCollection wIn = weightIh.range(new TraversalPolicy(dh, dh), 2 * rowStride);

			PackedCollection wHr = weightHh.range(new TraversalPolicy(dh, dh), 0);
			PackedCollection wHz = weightHh.range(new TraversalPolicy(dh, dh), rowStride);
			PackedCollection wHn = weightHh.range(new TraversalPolicy(dh, dh), 2 * rowStride);

			PackedCollection bIr = biasIh.range(new TraversalPolicy(dh), 0);
			PackedCollection bIz = biasIh.range(new TraversalPolicy(dh), dh);
			PackedCollection bIn = biasIh.range(new TraversalPolicy(dh), 2 * dh);

			PackedCollection bHr = biasHh.range(new TraversalPolicy(dh), 0);
			PackedCollection bHz = biasHh.range(new TraversalPolicy(dh), dh);
			PackedCollection bHn = biasHh.range(new TraversalPolicy(dh), 2 * dh);

			rGate[l] = compilePdslLayer(program, "gru_r_gate",
					new TraversalPolicy(2 * dh),
					mapOf("w_ir", wIr, "b_ir", bIr, "w_hr", wHr, "b_hr", bHr,
							"input_size", dh, "hidden_size", dh));
			zGate[l] = compilePdslLayer(program, "gru_z_gate",
					new TraversalPolicy(2 * dh),
					mapOf("w_iz", wIz, "b_iz", bIz, "w_hz", wHz, "b_hz", bHz,
							"input_size", dh, "hidden_size", dh));
			nGate[l] = compilePdslLayer(program, "gru_n_gate",
					new TraversalPolicy(3 * dh),
					mapOf("w_in", wIn, "b_in", bIn, "w_hn", wHn, "b_hn", bHn,
							"input_size", dh, "hidden_size", dh));
			hNew[l] = compilePdslLayer(program, "gru_h_new",
					new TraversalPolicy(3 * dh),
					mapOf("hidden_size", dh));
		}

		PackedCollection embedTable = stateDict.get("decoder_embedding.weight");

		// Synthetic transformer hidden state (all 0.1)
		PackedCollection transformerHidden = new PackedCollection(config.hiddenSize);
		for (int i = 0; i < config.hiddenSize; i++) {
			transformerHidden.setMem(i, 0.1);
		}

		// Run inference
		int[] outputTokens = runGruDecode(
				transformerHidden, summaryModel, rGate, zGate, nGate, hNew,
				lmHeadModel, embedTable, config.decoderHiddenSize,
				config.decodeVocabSize, GRUDecoder.TOKENS_PER_NOTE);

		Assert.assertEquals("Should produce 7 output tokens",
				GRUDecoder.TOKENS_PER_NOTE, outputTokens.length);

		int[] vocabOffsets = GRUDecoder.computeVocabOffsets(config);
		for (int i = 0; i < outputTokens.length; i++) {
			Assert.assertTrue("Token " + i + " >= 0", outputTokens[i] >= 0);
			Assert.assertTrue("Token " + i + " < decodeVocabSize",
					outputTokens[i] < config.decodeVocabSize);
		}

		// Convert to attribute values using static vocab offset utility
		int[] attrValues = new int[GRUDecoder.TOKENS_PER_NOTE];
		for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {
			attrValues[i] = outputTokens[i] - vocabOffsets[i];
		}

		System.out.println("[GruDecoderPdslInferenceTest] PDSL inference output tokens (flat vocab): "
				+ Arrays.toString(outputTokens));
		System.out.println("[GruDecoderPdslInferenceTest] Attribute values: "
				+ Arrays.toString(attrValues));
		System.out.println("[GruDecoderPdslInferenceTest] PDSL inference test passed.");
	}

	// -----------------------------------------------------------------------
	//  Core decode loop (test orchestration — all math is in PDSL)
	// -----------------------------------------------------------------------

	/**
	 * Orchestrate the 7-step GRU decode loop.
	 *
	 * <p>All neural-network math is performed by PDSL-compiled models. This
	 * method handles only data routing: the summary projection, the sequential
	 * step loop, input preparation (concatenation of x and h vectors), argmax
	 * token selection, and embedding lookup.</p>
	 *
	 * <p>The sequential loop cannot be expressed in static PDSL because each
	 * step's input embedding depends on the argmax of the previous step's logits
	 * (data-dependent control flow).</p>
	 *
	 * @param transformerHidden  transformer hidden state, shape (hiddenSize)
	 * @param summaryModel       compiled PDSL summary projection block
	 * @param rGate              compiled PDSL reset gate, one per GRU layer
	 * @param zGate              compiled PDSL update gate, one per GRU layer
	 * @param nGate              compiled PDSL candidate gate, one per GRU layer
	 * @param hNew               compiled PDSL hidden update, one per GRU layer
	 * @param lmHeadModel        compiled PDSL lm_head projection block
	 * @param embedTable         decoder embedding table, shape (vocabSize, dh)
	 * @param dh                 decoder hidden size
	 * @param vocabSize          decode vocabulary size
	 * @param numSteps           number of decode steps (7)
	 * @return output token indices in the flat decode vocabulary
	 */
	private static int[] runGruDecode(
			PackedCollection transformerHidden,
			CompiledModel summaryModel,
			CompiledModel[] rGate, CompiledModel[] zGate,
			CompiledModel[] nGate, CompiledModel[] hNew,
			CompiledModel lmHeadModel,
			PackedCollection embedTable,
			int dh, int vocabSize, int numSteps) {

		int numLayers = rGate.length;

		// Step 1: summary projection via PDSL (all computation in PDSL)
		PackedCollection initialHidden = summaryModel.forward(transformerHidden);

		// Initialize per-layer hidden states from summary projection
		PackedCollection[] h = new PackedCollection[numLayers];
		for (int l = 0; l < numLayers; l++) {
			h[l] = initialHidden.range(new TraversalPolicy(dh), 0);
		}

		// SOS output token (index 0) as initial embedding
		PackedCollection x = embedTable.range(new TraversalPolicy(dh), 0);

		int[] outputTokens = new int[numSteps];

		for (int step = 0; step < numSteps; step++) {
			// Forward through all GRU layers (all math via PDSL)
			for (int l = 0; l < numLayers; l++) {
				// Concatenate [x | h[l]] for r and z gates
				PackedCollection xh = concat(x, dh, h[l], dh);

				// PDSL r and z gates: σ(W@x + W@h)
				PackedCollection r = rGate[l].forward(xh);
				PackedCollection z = zGate[l].forward(xh);

				// Concatenate [x | h[l] | r] for n gate
				PackedCollection xhr = concatThree(x, h[l], r, dh);

				// PDSL n gate: tanh(W@x + r ⊙ W@h)
				PackedCollection n = nGate[l].forward(xhr);

				// Concatenate [n | z | h[l]] for h_new (lerp)
				PackedCollection nzh = concatThree(n, z, h[l], dh);

				// PDSL h_new: lerp(n, z, h)
				h[l] = hNew[l].forward(nzh);

				// Pass last layer output as input to next layer
				x = h[l];
			}

			// PDSL lm_head: project last layer output to vocab logits
			PackedCollection logits = lmHeadModel.forward(h[numLayers - 1]);

			// Argmax token selection (test code, not computation class)
			int token = argmax(logits, vocabSize);
			outputTokens[step] = token;

			// Embedding lookup for next step input (zero-copy range view)
			x = embedTable.range(new TraversalPolicy(dh), token * dh);
		}

		return outputTokens;
	}

	// -----------------------------------------------------------------------
	//  Utilities
	// -----------------------------------------------------------------------

	/**
	 * Load {@value PDSL_RESOURCE} from the classpath.
	 *
	 * @return parsed PDSL program
	 * @throws IOException if the resource cannot be read
	 */
	private PdslNode.Program loadPdslProgram() throws IOException {
		try (InputStream is = getClass().getResourceAsStream(PDSL_RESOURCE)) {
			Assert.assertNotNull("PDSL resource not found on classpath: " + PDSL_RESOURCE, is);
			PdslLoader loader = new PdslLoader();
			return loader.parse(new String(is.readAllBytes()));
		}
	}

	/**
	 * Build a PDSL layer and compile it into a {@link CompiledModel}.
	 *
	 * @param program    parsed PDSL program
	 * @param layerName  layer name as defined in the PDSL
	 * @param inputShape input tensor shape
	 * @param args       parameter bindings
	 * @return compiled model ready for forward pass
	 */
	private static CompiledModel compilePdslLayer(PdslNode.Program program,
												   String layerName,
												   TraversalPolicy inputShape,
												   Map<String, Object> args) {
		PdslLoader loader = new PdslLoader();
		Block block = loader.buildLayer(program, layerName, inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		return model.compile(false);
	}

	/**
	 * Concatenate two equal-size vectors into a new PackedCollection.
	 *
	 * @param a     first vector
	 * @param b     second vector
	 * @param size  number of elements in each vector
	 * @return new PackedCollection [a | b]
	 */
	private static PackedCollection concat(PackedCollection a, int sizeA,
										   PackedCollection b, int sizeB) {
		PackedCollection result = new PackedCollection(sizeA + sizeB);
		result.setMem(0, a, 0, sizeA);
		result.setMem(sizeA, b, 0, sizeB);
		return result;
	}

	/**
	 * Concatenate three equal-size vectors into a new PackedCollection.
	 *
	 * @param a    first vector
	 * @param b    second vector
	 * @param c    third vector
	 * @param size number of elements in each vector
	 * @return new PackedCollection [a | b | c]
	 */
	private static PackedCollection concatThree(PackedCollection a, PackedCollection b,
												  PackedCollection c, int size) {
		PackedCollection result = new PackedCollection(3 * size);
		result.setMem(0, a, 0, size);
		result.setMem(size, b, 0, size);
		result.setMem(2 * size, c, 0, size);
		return result;
	}

	/**
	 * Find the index of the maximum value in a collection.
	 *
	 * @param collection logits collection
	 * @param size       number of elements to check
	 * @return index of maximum element
	 */
	private static int argmax(PackedCollection collection, int size) {
		int maxIdx = 0;
		double maxVal = collection.toDouble(0);
		for (int i = 1; i < size; i++) {
			double val = collection.toDouble(i);
			if (val > maxVal) {
				maxVal = val;
				maxIdx = i;
			}
		}
		return maxIdx;
	}

	/**
	 * Create a random PackedCollection of given dimensions (small values near 0).
	 *
	 * @param rng  random number generator
	 * @param dims dimension sizes
	 * @return initialized PackedCollection
	 */
	private static PackedCollection randomCollection(Random rng, int... dims) {
		TraversalPolicy shape = new TraversalPolicy(dims);
		PackedCollection c = new PackedCollection(shape);
		int total = shape.getTotalSize();
		for (int i = 0; i < total; i++) {
			c.setMem(i, (rng.nextDouble() - 0.5) * 0.1);
		}
		return c;
	}

	/**
	 * Build a parameter map from alternating key/value pairs.
	 *
	 * @param keysAndValues alternating String key, Object value, ...
	 * @return parameter map
	 */
	private static Map<String, Object> mapOf(Object... keysAndValues) {
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i < keysAndValues.length - 1; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}
}
