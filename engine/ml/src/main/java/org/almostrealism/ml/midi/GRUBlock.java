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

package org.almostrealism.ml.midi;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.ml.dsl.PdslParseException;
import org.almostrealism.model.Block;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Weight holder for a single Gated Recurrent Unit (GRU) layer.
 *
 * <p>Loads the GRU computation graph from {@code /pdsl/gru_block.pdsl} at
 * construction time and exposes the four DSL-defined sub-blocks
 * ({@link #rGateBlock}, {@link #zGateBlock}, {@link #nGateBlock},
 * {@link #hNewBlock}) for use by inference code.</p>
 *
 * <p>Per-gate weight sub-matrices are zero-copy views derived by the PDSL
 * {@code data gru_weights} block via {@code range()} expressions — no
 * Java-side {@link PackedCollection#range} calls are needed here.</p>
 *
 * <h2>Weight Conventions</h2>
 * <ul>
 *   <li>{@code weightIh} = [W_ir; W_iz; W_in]  shape (3 * hiddenSize, inputSize)</li>
 *   <li>{@code weightHh} = [W_hr; W_hz; W_hn]  shape (3 * hiddenSize, hiddenSize)</li>
 *   <li>{@code biasIh}   = [b_ir; b_iz; b_in]  shape (3 * hiddenSize)</li>
 *   <li>{@code biasHh}   = [b_hr; b_hz; b_hn]  shape (3 * hiddenSize)</li>
 * </ul>
 */
public class GRUBlock {

	/** Classpath resource path for the GRU block PDSL definition. */
	private static final String GRU_PDSL_RESOURCE = "/pdsl/gru_block.pdsl";

	/** Dimension of the input vector. */
	private final int inputSize;

	/** Dimension of the hidden state. */
	private final int hiddenSize;

	// Zero-copy weight views derived from the PDSL gru_weights data block.
	// Exposed for use by GRUDecoder, which builds the GRU step as a
	// CellularLayer using raw producer expressions.

	/** Reset-gate input-hidden weight slice W_ir. */
	public final PackedCollection wIr;
	/** Reset-gate input-hidden bias slice b_ir. */
	public final PackedCollection bIr;
	/** Reset-gate hidden-hidden weight slice W_hr. */
	public final PackedCollection wHr;
	/** Reset-gate hidden-hidden bias slice b_hr. */
	public final PackedCollection bHr;

	/** Update-gate input-hidden weight slice W_iz. */
	public final PackedCollection wIz;
	/** Update-gate input-hidden bias slice b_iz. */
	public final PackedCollection bIz;
	/** Update-gate hidden-hidden weight slice W_hz. */
	public final PackedCollection wHz;
	/** Update-gate hidden-hidden bias slice b_hz. */
	public final PackedCollection bHz;

	/** Candidate-gate input-hidden weight slice W_in. */
	public final PackedCollection wIn;
	/** Candidate-gate input-hidden bias slice b_in. */
	public final PackedCollection bIn;
	/** Candidate-gate hidden-hidden weight slice W_hn. */
	public final PackedCollection wHn;
	/** Candidate-gate hidden-hidden bias slice b_hn. */
	public final PackedCollection bHn;

	/** Reset gate block (sigmoid of input + hidden projections). */
	final Block rGateBlock;
	/** Update gate block (sigmoid of input + hidden projections). */
	final Block zGateBlock;
	/** Candidate gate block (tanh with reset-gated hidden contribution). */
	final Block nGateBlock;
	/** Hidden state update block (lerp). */
	final Block hNewBlock;

	/**
	 * Create a GRU block with the given stacked weight tensors.
	 *
	 * @param inputSize  dimension of the input vector
	 * @param hiddenSize dimension of the hidden state
	 * @param weightIh   input-hidden weights (3 * hiddenSize, inputSize)
	 * @param weightHh   hidden-hidden weights (3 * hiddenSize, hiddenSize)
	 * @param biasIh     input-hidden bias (3 * hiddenSize)
	 * @param biasHh     hidden-hidden bias (3 * hiddenSize)
	 */
	public GRUBlock(int inputSize, int hiddenSize,
					PackedCollection weightIh, PackedCollection weightHh,
					PackedCollection biasIh, PackedCollection biasHh) {
		this.inputSize = inputSize;
		this.hiddenSize = hiddenSize;

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loadProgram(loader);

		Map<String, Object> args = new HashMap<>();
		args.put("weight_ih", weightIh);
		args.put("weight_hh", weightHh);
		args.put("bias_ih", biasIh);
		args.put("bias_hh", biasHh);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);

		// Derive zero-copy weight slices via the PDSL data block
		Map<String, Object> data = loader.evaluateDataDef(program, "gru_weights", args);
		wIr = (PackedCollection) data.get("w_ir");
		wIz = (PackedCollection) data.get("w_iz");
		wIn = (PackedCollection) data.get("w_in");
		wHr = (PackedCollection) data.get("w_hr");
		wHz = (PackedCollection) data.get("w_hz");
		wHn = (PackedCollection) data.get("w_hn");
		bIr = (PackedCollection) data.get("b_ir");
		bIz = (PackedCollection) data.get("b_iz");
		bIn = (PackedCollection) data.get("b_in");
		bHr = (PackedCollection) data.get("b_hr");
		bHz = (PackedCollection) data.get("b_hz");
		bHn = (PackedCollection) data.get("b_hn");

		// Build PDSL gate blocks; the data block entries are in scope for all layers
		rGateBlock = loader.buildLayer(program, "gru_r_gate",
				new TraversalPolicy(inputSize + hiddenSize), args);
		zGateBlock = loader.buildLayer(program, "gru_z_gate",
				new TraversalPolicy(inputSize + hiddenSize), args);
		nGateBlock = loader.buildLayer(program, "gru_n_gate",
				new TraversalPolicy(inputSize + 2 * hiddenSize), args);
		hNewBlock  = loader.buildLayer(program, "gru_h_new",
				new TraversalPolicy(3 * hiddenSize), args);
	}

	/** Returns the input dimension of this GRU block. */
	public int getInputSize() { return inputSize; }

	/** Returns the hidden state dimension of this GRU block. */
	public int getHiddenSize() { return hiddenSize; }

	/**
	 * Load and parse the GRU block PDSL program from the classpath resource.
	 *
	 * @param loader the PDSL loader to use for parsing
	 * @return the parsed program
	 */
	private static PdslNode.Program loadProgram(PdslLoader loader) {
		try (InputStream is = GRUBlock.class.getResourceAsStream(GRU_PDSL_RESOURCE)) {
			if (is == null) {
				throw new PdslParseException(
						"GRU block PDSL resource not found on classpath: " + GRU_PDSL_RESOURCE);
			}
			return loader.parse(new String(is.readAllBytes()));
		} catch (IOException e) {
			throw new PdslParseException(
					"Failed to load GRU block PDSL from " + GRU_PDSL_RESOURCE, e);
		}
	}
}
