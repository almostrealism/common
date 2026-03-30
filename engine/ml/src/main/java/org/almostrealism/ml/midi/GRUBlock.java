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
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.ml.dsl.PdslParseException;
import org.almostrealism.model.Block;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A single Gated Recurrent Unit (GRU) layer implemented as a {@link Block}
 * backed by Producer DSL computation graphs loaded from
 * {@code /pdsl/gru_block.pdsl} at construction time.
 *
 * <p>The GRU step computes:</p>
 * <pre>
 * r = σ(W_ir @ x + b_ir + W_hr @ h + b_hr)           // reset gate
 * z = σ(W_iz @ x + b_iz + W_hz @ h + b_hz)           // update gate
 * n = φ(W_in @ x + b_in + r ⊙ (W_hn @ h + b_hn))    // new gate (φ = tanh)
 * h' = n + z * (h - n)                                // hidden state update
 * </pre>
 *
 * <p>All computation is defined in {@code gru_block.pdsl} using low-level
 * primitives ({@code dense}, {@code sigmoid}, {@code tanh_act}, {@code slice},
 * {@code add_blocks}, {@code product}, {@code lerp}). This class is a
 * <em>loader only</em> — it loads the four DSL sub-graphs at construction time
 * and stores them as uncompiled {@link Block} objects for use by
 * {@link GRUDecoder}. No math is performed here; all execution happens in the
 * {@link GRUDecoder} orchestration layer.</p>
 *
 * <p>The four uncompiled sub-blocks ({@link #rGateBlock}, {@link #zGateBlock},
 * {@link #nGateBlock}, {@link #hNewBlock}) are package-accessible so that
 * {@link GRUDecoder} can compile them into
 * {@link org.almostrealism.model.CompiledModel} instances and orchestrate
 * each GRU step.</p>
 *
 * @see GRUDecoder
 */
public class GRUBlock implements Block {

	/** Classpath resource path for the GRU block PDSL definition. */
	private static final String GRU_PDSL_RESOURCE = "/pdsl/gru_block.pdsl";

	private final int inputSize;
	private final int hiddenSize;

	/**
	 * Per-gate weight sub-matrices extracted from the stacked constructor arguments
	 * using {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)}.
	 * No raw arrays are used in this splitting.
	 */
	final PackedCollection wIr, bIr, wHr, bHr;  // reset gate
	final PackedCollection wIz, bIz, wHz, bHz;  // update gate
	final PackedCollection wIn, bIn, wHn, bHn;  // candidate gate

	/**
	 * Uncompiled DSL sub-blocks — one per GRU sub-computation.
	 * Package-accessible so {@link GRUDecoder} can compile them and call
	 * {@code forward()} on the resulting {@link org.almostrealism.model.CompiledModel}.
	 * No execution happens in this class; execution is the responsibility of
	 * the {@link GRUDecoder} orchestration layer.
	 */
	final Block rGateBlock;
	final Block zGateBlock;
	final Block nGateBlock;
	final Block hNewBlock;

	/**
	 * Create a GRU block with the given weights.
	 *
	 * <p>Weight matrices follow PyTorch stacking convention:</p>
	 * <ul>
	 *   <li>{@code weightIh} = [W_ir; W_iz; W_in]  shape (3 * hiddenSize, inputSize)</li>
	 *   <li>{@code weightHh} = [W_hr; W_hz; W_hn]  shape (3 * hiddenSize, hiddenSize)</li>
	 *   <li>{@code biasIh}   = [b_ir; b_iz; b_in]  shape (3 * hiddenSize)</li>
	 *   <li>{@code biasHh}   = [b_hr; b_hz; b_hn]  shape (3 * hiddenSize)</li>
	 * </ul>
	 *
	 * <p>Weight sub-matrices are extracted using
	 * {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)};
	 * no raw arrays are created.</p>
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

		wIr = sliceMatrix(weightIh, 0,      hiddenSize, inputSize,  rs);
		wIz = sliceMatrix(weightIh, rs,     hiddenSize, inputSize,  rs);
		wIn = sliceMatrix(weightIh, 2 * rs, hiddenSize, inputSize,  rs);

		wHr = sliceMatrix(weightHh, 0,      hiddenSize, hiddenSize, rh);
		wHz = sliceMatrix(weightHh, rh,     hiddenSize, hiddenSize, rh);
		wHn = sliceMatrix(weightHh, 2 * rh, hiddenSize, hiddenSize, rh);

		bIr = sliceVector(biasIh, 0,           hiddenSize);
		bIz = sliceVector(biasIh, hiddenSize,   hiddenSize);
		bIn = sliceVector(biasIh, 2*hiddenSize, hiddenSize);

		bHr = sliceVector(biasHh, 0,           hiddenSize);
		bHz = sliceVector(biasHh, hiddenSize,   hiddenSize);
		bHn = sliceVector(biasHh, 2*hiddenSize, hiddenSize);

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

	// ---- Block interface ----

	/**
	 * Input shape is the concatenated [x; h] vector used by the r and z gates.
	 */
	@Override
	public TraversalPolicy getInputShape() { return shape(inputSize + hiddenSize); }

	/**
	 * Output shape is the new hidden state h'.
	 */
	@Override
	public TraversalPolicy getOutputShape() { return shape(hiddenSize); }

	/**
	 * GRUBlock is not used as a standard sequential Block in a larger pipeline.
	 * The {@link GRUDecoder} orchestrates GRU steps by compiling the four
	 * sub-blocks ({@link #rGateBlock}, {@link #zGateBlock}, {@link #nGateBlock},
	 * {@link #hNewBlock}) and managing the hidden state between steps.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public Cell<PackedCollection> getForward() {
		throw new UnsupportedOperationException(
				"GRUBlock is used through GRUDecoder orchestration, not a standard forward pipeline.");
	}

	/**
	 * Backpropagation is not supported for GRUBlock.
	 *
	 * @return null
	 */
	@Override
	public Cell<PackedCollection> getBackward() { return null; }

	/** {@inheritDoc} */
	@Override
	public Supplier<Runnable> setup() { return new OperationList(); }

	// ---- PDSL loading ----

	/**
	 * Load the GRU block PDSL program from the classpath resource.
	 *
	 * @param loader the PDSL loader
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

	// ---- DSL block builders ----

	private Block buildRGate(PdslLoader loader, PdslNode.Program program) {
		TraversalPolicy inputShape = shape(inputSize + hiddenSize);
		Map<String, Object> args = new HashMap<>();
		args.put("w_ir", wIr);
		args.put("b_ir", bIr);
		args.put("w_hr", wHr);
		args.put("b_hr", bHr);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		return loader.buildLayer(program, "gru_r_gate", inputShape, args);
	}

	private Block buildZGate(PdslLoader loader, PdslNode.Program program) {
		TraversalPolicy inputShape = shape(inputSize + hiddenSize);
		Map<String, Object> args = new HashMap<>();
		args.put("w_iz", wIz);
		args.put("b_iz", bIz);
		args.put("w_hz", wHz);
		args.put("b_hz", bHz);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		return loader.buildLayer(program, "gru_z_gate", inputShape, args);
	}

	private Block buildNGate(PdslLoader loader, PdslNode.Program program) {
		TraversalPolicy inputShape = shape(inputSize + 2 * hiddenSize);
		Map<String, Object> args = new HashMap<>();
		args.put("w_in", wIn);
		args.put("b_in", bIn);
		args.put("w_hn", wHn);
		args.put("b_hn", bHn);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		return loader.buildLayer(program, "gru_n_gate", inputShape, args);
	}

	private Block buildHNew(PdslLoader loader, PdslNode.Program program) {
		TraversalPolicy inputShape = shape(3 * hiddenSize);
		Map<String, Object> args = new HashMap<>();
		args.put("hidden_size", hiddenSize);
		return loader.buildLayer(program, "gru_h_new", inputShape, args);
	}

	// ---- Weight slicing helpers ----

	/**
	 * Extract a sub-matrix from a packed weight tensor using
	 * {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)}.
	 *
	 * @param src    source weight tensor
	 * @param offset element offset into src
	 * @param rows   number of rows
	 * @param cols   number of columns
	 * @param size   total number of elements (rows * cols)
	 * @return new PackedCollection of shape (rows, cols)
	 */
	private static PackedCollection sliceMatrix(PackedCollection src, int offset,
												 int rows, int cols, int size) {
		PackedCollection result = new PackedCollection(new TraversalPolicy(rows, cols));
		result.setMem(0, src, offset, size);
		return result;
	}

	/**
	 * Extract a sub-vector from a packed bias tensor using
	 * {@link PackedCollection#setMem(int, org.almostrealism.hardware.MemoryData, int, int)}.
	 *
	 * @param src    source bias tensor
	 * @param offset element offset into src
	 * @param size   number of elements
	 * @return new PackedCollection of shape (size)
	 */
	private static PackedCollection sliceVector(PackedCollection src, int offset, int size) {
		PackedCollection result = new PackedCollection(new TraversalPolicy(size));
		result.setMem(0, src, offset, size);
		return result;
	}
}
