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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A single Gated Recurrent Unit (GRU) cell backed by Producer DSL computation graphs.
 *
 * <p>The GRU cell computes:</p>
 * <pre>
 * r = sigmoid(W_ir @ x + b_ir + W_hr @ h + b_hr)     // reset gate
 * z = sigmoid(W_iz @ x + b_iz + W_hz @ h + b_hz)     // update gate
 * n = tanh(W_in @ x + b_in + r * (W_hn @ h + b_hn))  // new gate
 * h' = n + z * (h - n)                                // new hidden state (lerp)
 * </pre>
 *
 * <p>The computation is expressed as four Producer DSL layers loaded from an embedded
 * PDSL program. Each layer is compiled into a {@link CompiledModel} at construction
 * time. No {@code evaluate()}, {@code matmul()}, {@code sigmoid()}, {@code tanh()},
 * {@code add()}, or {@code multiply()} calls appear in this class; all math is
 * defined in the DSL source and executed via {@link CompiledModel#forward}.</p>
 *
 * <p>The weight matrices from the stacked PyTorch convention are split into
 * per-gate sub-matrices at construction time using bulk array operations.</p>
 *
 * <p>This class implements {@link Block} with the concatenated {@code [x;h]} input
 * as the block input shape and {@code h'} as the output shape. The primary inference
 * entry point is {@link #forward(PackedCollection, PackedCollection)}.</p>
 *
 * @see GRUDecoder
 */
public class GRUCell implements Block {

	/**
	 * Embedded PDSL source defining the four GRU sub-layers.
	 *
	 * <p>Layers:
	 * <ul>
	 *   <li>{@code gru_r_gate} – input [x;h] → reset gate r</li>
	 *   <li>{@code gru_z_gate} – input [x;h] → update gate z</li>
	 *   <li>{@code gru_n_gate} – input [x;h;r] → candidate state n</li>
	 *   <li>{@code gru_h_new}  – input [n;z;h] → new hidden state h'</li>
	 * </ul>
	 */
	private static final String GRU_PDSL = "" +
			"// r gate: sigmoid(W_ir@x + b_ir + W_hr@h + b_hr)\n" +
			"layer gru_r_gate(w_ir: weight, b_ir: weight, w_hr: weight, b_hr: weight,\n" +
			"                 input_size: int, hidden_size: int) {\n" +
			"    add_blocks(\n" +
			"        { slice(0, input_size); dense(w_ir, b_ir) },\n" +
			"        { slice(input_size, hidden_size); dense(w_hr, b_hr) }\n" +
			"    )\n" +
			"    sigmoid()\n" +
			"}\n" +
			"\n" +
			"// z gate: sigmoid(W_iz@x + b_iz + W_hz@h + b_hz)\n" +
			"layer gru_z_gate(w_iz: weight, b_iz: weight, w_hz: weight, b_hz: weight,\n" +
			"                 input_size: int, hidden_size: int) {\n" +
			"    add_blocks(\n" +
			"        { slice(0, input_size); dense(w_iz, b_iz) },\n" +
			"        { slice(input_size, hidden_size); dense(w_hz, b_hz) }\n" +
			"    )\n" +
			"    sigmoid()\n" +
			"}\n" +
			"\n" +
			"// n gate: tanh(W_in@x + b_in + r*(W_hn@h + b_hn))\n" +
			"// Input: [x(input_size) | h(hidden_size) | r(hidden_size)]\n" +
			"layer gru_n_gate(w_in: weight, b_in: weight, w_hn: weight, b_hn: weight,\n" +
			"                 input_size: int, hidden_size: int) {\n" +
			"    add_blocks(\n" +
			"        product({ slice(input_size + hidden_size, hidden_size) },\n" +
			"                { slice(input_size, hidden_size); dense(w_hn, b_hn) }),\n" +
			"        { slice(0, input_size); dense(w_in, b_in) }\n" +
			"    )\n" +
			"    tanh_act()\n" +
			"}\n" +
			"\n" +
			"// h_new: n + z*(h-n)  via lerp([n|z|h])\n" +
			"// Input: [n(hidden_size) | z(hidden_size) | h(hidden_size)]\n" +
			"layer gru_h_new(hidden_size: int) {\n" +
			"    lerp(hidden_size)\n" +
			"}\n";

	private final int inputSize;
	private final int hiddenSize;

	/** Per-gate weight sub-matrices extracted from the stacked constructor arguments. */
	private final PackedCollection wIr, bIr, wHr, bHr;  // reset gate
	private final PackedCollection wIz, bIz, wHz, bHz;  // update gate
	private final PackedCollection wIn, bIn, wHn, bHn;  // candidate gate

	/** Compiled DSL models – one per GRU sub-computation. */
	private final CompiledModel rGateModel;
	private final CompiledModel zGateModel;
	private final CompiledModel nGateModel;
	private final CompiledModel hNewModel;

	/**
	 * Create a GRU cell with the given weights.
	 *
	 * <p>Weight matrices follow PyTorch stacking convention:</p>
	 * <ul>
	 *   <li>{@code weightIh} = [W_ir; W_iz; W_in]  shape (3 * hiddenSize, inputSize)</li>
	 *   <li>{@code weightHh} = [W_hr; W_hz; W_hn]  shape (3 * hiddenSize, hiddenSize)</li>
	 *   <li>{@code biasIh}   = [b_ir; b_iz; b_in]  shape (3 * hiddenSize)</li>
	 *   <li>{@code biasHh}   = [b_hr; b_hz; b_hn]  shape (3 * hiddenSize)</li>
	 * </ul>
	 *
	 * @param inputSize  dimension of the input vector
	 * @param hiddenSize dimension of the hidden state
	 * @param weightIh   input-hidden weights (3 * hiddenSize, inputSize)
	 * @param weightHh   hidden-hidden weights (3 * hiddenSize, hiddenSize)
	 * @param biasIh     input-hidden bias (3 * hiddenSize)
	 * @param biasHh     hidden-hidden bias (3 * hiddenSize)
	 */
	public GRUCell(int inputSize, int hiddenSize,
				   PackedCollection weightIh, PackedCollection weightHh,
				   PackedCollection biasIh, PackedCollection biasHh) {
		this.inputSize = inputSize;
		this.hiddenSize = hiddenSize;

		// Split stacked weight matrices into per-gate sub-matrices using bulk array ops
		int rs = hiddenSize * inputSize;
		int rh = hiddenSize * hiddenSize;
		double[] wIhData = weightIh.toArray(0, 3 * rs);
		double[] wHhData = weightHh.toArray(0, 3 * rh);
		double[] bIhData = biasIh.toArray(0, 3 * hiddenSize);
		double[] bHhData = biasHh.toArray(0, 3 * hiddenSize);

		wIr = packMatrix(wIhData, 0,      hiddenSize, inputSize);
		wIz = packMatrix(wIhData, rs,     hiddenSize, inputSize);
		wIn = packMatrix(wIhData, 2 * rs, hiddenSize, inputSize);

		wHr = packMatrix(wHhData, 0,      hiddenSize, hiddenSize);
		wHz = packMatrix(wHhData, rh,     hiddenSize, hiddenSize);
		wHn = packMatrix(wHhData, 2 * rh, hiddenSize, hiddenSize);

		bIr = packVector(bIhData, 0,           hiddenSize);
		bIz = packVector(bIhData, hiddenSize,   hiddenSize);
		bIn = packVector(bIhData, 2*hiddenSize, hiddenSize);

		bHr = packVector(bHhData, 0,           hiddenSize);
		bHz = packVector(bHhData, hiddenSize,   hiddenSize);
		bHn = packVector(bHhData, 2*hiddenSize, hiddenSize);

		// Parse DSL and compile one model per sub-computation (backprop not needed)
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(GRU_PDSL);

		rGateModel = buildRGate(loader, program).compile(false);
		zGateModel = buildZGate(loader, program).compile(false);
		nGateModel = buildNGate(loader, program).compile(false);
		hNewModel  = buildHNew(loader, program).compile(false);
	}

	/**
	 * Compute one GRU step via four DSL-compiled sub-models.
	 *
	 * <p>Inputs are concatenated and fed through the DSL models sequentially.
	 * No {@code evaluate()}, {@code matmul()}, {@code sigmoid()}, {@code tanh()},
	 * {@code add()}, or {@code multiply()} is called from this class.</p>
	 *
	 * @param x input vector of size (inputSize)
	 * @param h previous hidden state of size (hiddenSize)
	 * @return new hidden state h' of size (hiddenSize)
	 */
	public PackedCollection forward(PackedCollection x, PackedCollection h) {
		// [x; h] concatenated for r and z gates
		PackedCollection xh = concatVectors(x, h, inputSize, hiddenSize);

		PackedCollection r = rGateModel.forward(xh);
		PackedCollection z = zGateModel.forward(xh);

		// [x; h; r] for n gate
		PackedCollection xhr = concatVectors(xh, r, inputSize + hiddenSize, hiddenSize);
		PackedCollection n = nGateModel.forward(xhr);

		// [n; z; h] for h_new (lerp)
		PackedCollection nzh = concatThree(n, z, h, hiddenSize);
		return hNewModel.forward(nzh);
	}

	/** Returns the input dimension of this GRU cell. */
	public int getInputSize() { return inputSize; }

	/** Returns the hidden state dimension of this GRU cell. */
	public int getHiddenSize() { return hiddenSize; }

	// ---- Block interface ----

	/** Input shape is the concatenated [x; h] vector. */
	@Override
	public TraversalPolicy getInputShape() { return shape(inputSize + hiddenSize); }

	/** Output shape is the new hidden state h'. */
	@Override
	public TraversalPolicy getOutputShape() { return shape(hiddenSize); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>The forward cell for GRUCell is not implemented; use {@link #forward(PackedCollection,
	 * PackedCollection)} directly for inference.</p>
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public Cell<PackedCollection> getForward() {
		throw new UnsupportedOperationException(
				"GRUCell.getForward() is not supported; use forward(x, h) for inference");
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Backpropagation is not supported for GRUCell.</p>
	 */
	@Override
	public Cell<PackedCollection> getBackward() { return null; }

	@Override
	public Supplier<Runnable> setup() { return new OperationList(); }

	// ---- DSL model builders ----

	private Model buildRGate(PdslLoader loader, PdslNode.Program program) {
		TraversalPolicy inputShape = shape(inputSize + hiddenSize);
		Map<String, Object> args = new HashMap<>();
		args.put("w_ir", wIr);
		args.put("b_ir", bIr);
		args.put("w_hr", wHr);
		args.put("b_hr", bHr);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		Block block = loader.buildLayer(program, "gru_r_gate", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		return model;
	}

	private Model buildZGate(PdslLoader loader, PdslNode.Program program) {
		TraversalPolicy inputShape = shape(inputSize + hiddenSize);
		Map<String, Object> args = new HashMap<>();
		args.put("w_iz", wIz);
		args.put("b_iz", bIz);
		args.put("w_hz", wHz);
		args.put("b_hz", bHz);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		Block block = loader.buildLayer(program, "gru_z_gate", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		return model;
	}

	private Model buildNGate(PdslLoader loader, PdslNode.Program program) {
		TraversalPolicy inputShape = shape(inputSize + 2 * hiddenSize);
		Map<String, Object> args = new HashMap<>();
		args.put("w_in", wIn);
		args.put("b_in", bIn);
		args.put("w_hn", wHn);
		args.put("b_hn", bHn);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);
		Block block = loader.buildLayer(program, "gru_n_gate", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		return model;
	}

	private Model buildHNew(PdslLoader loader, PdslNode.Program program) {
		TraversalPolicy inputShape = shape(3 * hiddenSize);
		Map<String, Object> args = new HashMap<>();
		args.put("hidden_size", hiddenSize);
		Block block = loader.buildLayer(program, "gru_h_new", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		return model;
	}

	// ---- Private bulk-copy helpers (no forbidden math operations) ----

	/**
	 * Create a PackedCollection wrapping a sub-matrix from a flat double array.
	 *
	 * @param src    source array
	 * @param offset element offset into src
	 * @param rows   number of rows
	 * @param cols   number of columns
	 * @return new PackedCollection of shape (rows, cols)
	 */
	private static PackedCollection packMatrix(double[] src, int offset, int rows, int cols) {
		PackedCollection result = new PackedCollection(new TraversalPolicy(rows, cols));
		result.setMem(0, src, offset, rows * cols);
		return result;
	}

	/**
	 * Create a PackedCollection wrapping a sub-vector from a flat double array.
	 *
	 * @param src    source array
	 * @param offset element offset into src
	 * @param size   number of elements
	 * @return new PackedCollection of shape (size)
	 */
	private static PackedCollection packVector(double[] src, int offset, int size) {
		PackedCollection result = new PackedCollection(new TraversalPolicy(size));
		result.setMem(0, src, offset, size);
		return result;
	}

	/**
	 * Concatenate two vectors into a new flat PackedCollection [a; b].
	 *
	 * @param a    first vector
	 * @param b    second vector
	 * @param sizeA number of elements in a
	 * @param sizeB number of elements in b
	 * @return concatenated PackedCollection of shape (sizeA + sizeB)
	 */
	private static PackedCollection concatVectors(PackedCollection a, PackedCollection b,
												   int sizeA, int sizeB) {
		PackedCollection result = new PackedCollection(new TraversalPolicy(sizeA + sizeB));
		double[] aData = a.toArray(0, sizeA);
		double[] bData = b.toArray(0, sizeB);
		result.setMem(0, aData, 0, sizeA);
		result.setMem(sizeA, bData, 0, sizeB);
		return result;
	}

	/**
	 * Concatenate three equal-sized vectors into [a; b; c].
	 *
	 * @param a    first vector
	 * @param b    second vector
	 * @param c    third vector
	 * @param size number of elements in each vector
	 * @return concatenated PackedCollection of shape (3 * size)
	 */
	private static PackedCollection concatThree(PackedCollection a, PackedCollection b,
												 PackedCollection c, int size) {
		PackedCollection result = new PackedCollection(new TraversalPolicy(3 * size));
		double[] aData = a.toArray(0, size);
		double[] bData = b.toArray(0, size);
		double[] cData = c.toArray(0, size);
		result.setMem(0,        aData, 0, size);
		result.setMem(size,     bData, 0, size);
		result.setMem(2 * size, cData, 0, size);
		return result;
	}
}
