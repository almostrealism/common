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
 * <p>Weight sub-matrices are zero-copy views into the stacked PyTorch weight
 * tensors, obtained via {@link PackedCollection#range(TraversalPolicy, int)}.</p>
 *
 * <p>This class does not perform any computation and does not compile any models.
 * All four gate {@link Block} objects are assembled into the caller's single
 * {@link org.almostrealism.model.Model} by {@link GRUDecoder}.</p>
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

	private final int inputSize;
	private final int hiddenSize;

	/** Zero-copy weight views via {@link PackedCollection#range(TraversalPolicy, int)}. */
	public final PackedCollection wIr, bIr, wHr, bHr;  // reset gate
	public final PackedCollection wIz, bIz, wHz, bHz;  // update gate
	public final PackedCollection wIn, bIn, wHn, bHn;  // candidate gate

	/**
	 * Uncompiled DSL sub-blocks, one per GRU sub-computation.
	 * Loaded from {@code gru_block.pdsl} at construction time.
	 * These are assembled into the single decode-step model by {@link GRUDecoder}.
	 */
	final Block rGateBlock;
	final Block zGateBlock;
	final Block nGateBlock;
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

		int rs = hiddenSize * inputSize;
		int rh = hiddenSize * hiddenSize;

		// Zero-copy weight views using range()
		wIr = weightIh.range(new TraversalPolicy(hiddenSize, inputSize),  0);
		wIz = weightIh.range(new TraversalPolicy(hiddenSize, inputSize),  rs);
		wIn = weightIh.range(new TraversalPolicy(hiddenSize, inputSize),  2 * rs);

		wHr = weightHh.range(new TraversalPolicy(hiddenSize, hiddenSize), 0);
		wHz = weightHh.range(new TraversalPolicy(hiddenSize, hiddenSize), rh);
		wHn = weightHh.range(new TraversalPolicy(hiddenSize, hiddenSize), 2 * rh);

		bIr = biasIh.range(new TraversalPolicy(hiddenSize), 0);
		bIz = biasIh.range(new TraversalPolicy(hiddenSize), hiddenSize);
		bIn = biasIh.range(new TraversalPolicy(hiddenSize), 2 * hiddenSize);

		bHr = biasHh.range(new TraversalPolicy(hiddenSize), 0);
		bHz = biasHh.range(new TraversalPolicy(hiddenSize), hiddenSize);
		bHn = biasHh.range(new TraversalPolicy(hiddenSize), 2 * hiddenSize);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loadProgram(loader);

		rGateBlock = buildRGate(loader, program);
		zGateBlock = buildZGate(loader, program);
		nGateBlock = buildNGate(loader, program);
		hNewBlock  = buildHNew(loader, program);
	}

	/** Returns the input dimension of this GRU block. */
	public int getInputSize() { return inputSize; }

	/** Returns the hidden state dimension of this GRU block. */
	public int getHiddenSize() { return hiddenSize; }

	// ---- PDSL loading ----

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

	// ---- DSL block builders ----

	private Block buildRGate(PdslLoader loader, PdslNode.Program program) {
		Map<String, Object> args = new HashMap<>();
		args.put("w_ir", wIr);
		args.put("b_ir", bIr);
		args.put("w_hr", wHr);
		args.put("b_hr", bHr);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		return loader.buildLayer(program, "gru_r_gate",
				new TraversalPolicy(inputSize + hiddenSize), args);
	}

	private Block buildZGate(PdslLoader loader, PdslNode.Program program) {
		Map<String, Object> args = new HashMap<>();
		args.put("w_iz", wIz);
		args.put("b_iz", bIz);
		args.put("w_hz", wHz);
		args.put("b_hz", bHz);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		return loader.buildLayer(program, "gru_z_gate",
				new TraversalPolicy(inputSize + hiddenSize), args);
	}

	private Block buildNGate(PdslLoader loader, PdslNode.Program program) {
		Map<String, Object> args = new HashMap<>();
		args.put("w_in", wIn);
		args.put("b_in", bIn);
		args.put("w_hn", wHn);
		args.put("b_hn", bHn);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		return loader.buildLayer(program, "gru_n_gate",
				new TraversalPolicy(inputSize + 2 * hiddenSize), args);
	}

	private Block buildHNew(PdslLoader loader, PdslNode.Program program) {
		Map<String, Object> args = new HashMap<>();
		args.put("hidden_size", hiddenSize);
		return loader.buildLayer(program, "gru_h_new",
				new TraversalPolicy(3 * hiddenSize), args);
	}
}
