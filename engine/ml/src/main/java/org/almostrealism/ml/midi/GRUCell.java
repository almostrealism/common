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
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.ml.dsl.PdslParseException;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A single Gated Recurrent Unit (GRU) cell backed by Producer DSL computation graphs
 * loaded from {@code /pdsl/gru_cell.pdsl} at runtime.
 *
 * <p>The GRU cell computes:</p>
 * <pre>
 * r = σ(W_ir @ x + b_ir + W_hr @ h + b_hr)           // reset gate
 * z = σ(W_iz @ x + b_iz + W_hz @ h + b_hz)           // update gate
 * n = φ(W_in @ x + b_in + r ⊙ (W_hn @ h + b_hn))    // new gate (φ = tanh activation)
 * h' = n + z * (h - n)                                // new hidden state (lerp)
 * </pre>
 *
 * <p>All computation is defined in {@code gru_cell.pdsl} using only low-level
 * primitives ({@code dense}, {@code sigmoid}, {@code tanh_act}, {@code slice},
 * {@code add_blocks}, {@code product}, {@code lerp}). Each sub-layer is compiled
 * into a {@link CompiledModel} at construction time.</p>
 *
 * <p>This class implements both {@link Cell} and {@link Block}. The {@link Block}
 * view exposes input shape {@code [x;h]} and output shape {@code h'}. The
 * {@link Cell} view allows GRUCell to be wired into graph-based computation
 * pipelines. The primary inference entry point for autoregressive decoding is
 * {@link #forward(PackedCollection, PackedCollection)}.</p>
 *
 * @see GRUDecoder
 */
public class GRUCell implements Cell<PackedCollection>, Block {

	/** Classpath resource path for the GRU cell PDSL definition. */
	private static final String GRU_PDSL_RESOURCE = "/pdsl/gru_cell.pdsl";

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

	/** Downstream receptor for Cell-based graph wiring. */
	private Receptor<PackedCollection> receptor;

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

		// Load the DSL from the classpath resource and compile one model per sub-computation
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loadProgram(loader);

		rGateModel = buildRGate(loader, program).compile(false);
		zGateModel = buildZGate(loader, program).compile(false);
		nGateModel = buildNGate(loader, program).compile(false);
		hNewModel  = buildHNew(loader, program).compile(false);
	}

	/**
	 * Compute one GRU step via four DSL-compiled sub-models.
	 *
	 * <p>All math (matmul, sigmoid, tanh, element-wise multiply, lerp) is
	 * defined in {@code gru_cell.pdsl} and executed through the compiled models.
	 * This method performs only data routing: concatenation of inputs for each
	 * sub-model and dispatch to {@link CompiledModel#forward}.</p>
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
	 * Returns this cell as the forward processing unit.
	 *
	 * <p>GRUCell implements {@link Cell} directly so {@link #push} handles
	 * data flow for graph-wiring use cases. The primary inference path uses
	 * {@link #forward(PackedCollection, PackedCollection)} called from
	 * {@link GRUDecoder}.</p>
	 *
	 * @return this
	 */
	@Override
	public Cell<PackedCollection> getForward() {
		return this;
	}

	/**
	 * Backpropagation is not supported for GRUCell.
	 *
	 * @return null
	 */
	@Override
	public Cell<PackedCollection> getBackward() { return null; }

	/** {@inheritDoc} */
	@Override
	public Supplier<Runnable> setup() { return new OperationList(); }

	// ---- Cell interface ----

	/**
	 * Resolve the ambiguity between {@link Cell#apply} and
	 * {@link org.almostrealism.graph.CellularPropagation#apply}.
	 *
	 * <p>{@link org.almostrealism.graph.CellularPropagation#apply} delegates to
	 * {@link #getForward()}, which returns {@code this}, causing infinite
	 * recursion. Delegating to {@link Cell#apply} avoids this.</p>
	 *
	 * @param input the input data producer
	 * @return the output producer
	 */
	@Override
	public Producer<PackedCollection> apply(Producer<PackedCollection> input) {
		return Cell.super.apply(input);
	}

	/**
	 * Store the downstream receptor for Cell-based graph wiring.
	 *
	 * @param r the downstream receptor
	 */
	@Override
	public void setReceptor(Receptor<PackedCollection> r) {
		this.receptor = r;
	}

	/**
	 * Satisfies the {@link Cell} push interface for graph-wiring compatibility.
	 *
	 * <p>The primary inference path for GRU decoding uses
	 * {@link #forward(PackedCollection, PackedCollection)} called from
	 * {@link GRUDecoder}, which manages the autoregressive hidden state.
	 * Caller-managed hidden state cannot be expressed through the single-input
	 * push protocol, so this method returns an empty operation.</p>
	 *
	 * @param protein the input producer (not used; see forward for inference)
	 * @return an empty operation list
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		return new OperationList();
	}

	// ---- PDSL loading ----

	/**
	 * Load the GRU cell PDSL program from the classpath resource.
	 *
	 * @param loader the PDSL loader to parse with
	 * @return the parsed program
	 * @throws PdslParseException if the resource is not found or cannot be parsed
	 */
	private static PdslNode.Program loadProgram(PdslLoader loader) {
		try (InputStream is = GRUCell.class.getResourceAsStream(GRU_PDSL_RESOURCE)) {
			if (is == null) {
				throw new PdslParseException(
						"GRU cell PDSL resource not found on classpath: " + GRU_PDSL_RESOURCE);
			}
			return loader.parse(new String(is.readAllBytes()));
		} catch (IOException e) {
			throw new PdslParseException(
					"Failed to load GRU cell PDSL from " + GRU_PDSL_RESOURCE, e);
		}
	}

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

	// ---- Private bulk-copy helpers ----

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
	 * @param a     first vector
	 * @param b     second vector
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
