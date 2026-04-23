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

package org.almostrealism.ml.dsl;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;

/**
 * Compiled output of a PDSL {@code pipeline} definition.
 *
 * <p>Implements both {@link Temporal} (primary path — for CellList integration via
 * {@link org.almostrealism.audio.CellList#addRequirement(Temporal)}) and
 * {@link Cell}{@code <PackedCollection<?>>} (secondary path — for the Block-to-CellList
 * adapter pattern).</p>
 *
 * <h2>Temporal path (primary)</h2>
 * <p>{@link #tick()} reads from the attached input {@link PackedCollection} buffer,
 * runs the compiled forward pass, and pushes the result to the attached output
 * {@link Receptor}. Both must be bound before calling {@link #tick()}</p>
 *
 * <h2>Cell path (secondary)</h2>
 * <p>{@link #push(Producer)} receives an upstream producer, runs the compiled forward
 * pass with that input, and forwards the result to the downstream receptor set via
 * {@link #setReceptor(Receptor)}.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * PdslTemporalBlock pipeline = loader.buildPipeline(program, "mixdown_main", shape, args);
 * pipeline.attachInput("channel_audio", patternAudioBuffer);
 * pipeline.attachOutput("master_output", masterReceptor);
 * cells.addRequirement(pipeline);  // fires once per buffer
 * }</pre>
 *
 * @see PdslLoader#buildPipeline(PdslNode.Program, String, io.almostrealism.collect.TraversalPolicy, java.util.Map)
 * @see org.almostrealism.time.Temporal
 * @see Cell
 */
public class PdslTemporalBlock implements Temporal, Cell<PackedCollection<?>> {

	/** The compiled forward-pass kernel for this pipeline. */
	private final CompiledModel compiledModel;

	/** Declared input name from the PDSL {@code input <name> -> <shape>} declaration. */
	private final String inputName;

	/** Declared output name from the PDSL {@code output <name>} declaration. */
	private final String outputName;

	/** Input buffer bound via {@link #attachInput(String, PackedCollection)}. */
	private PackedCollection<?> inputBuffer;

	/** Output receptor bound via {@link #attachOutput(String, Receptor)}. */
	private Receptor<PackedCollection<?>> outputReceptor;

	/** Downstream receptor for the Cell path, set via {@link #setReceptor(Receptor)}. */
	private Receptor<PackedCollection<?>> downstreamReceptor;

	/**
	 * Constructs a temporal block wrapping the given compiled model.
	 *
	 * @param compiledModel the compiled pipeline forward pass
	 * @param inputName     declared input name (from PDSL {@code input} declaration)
	 * @param outputName    declared output name (from PDSL {@code output} declaration)
	 */
	public PdslTemporalBlock(CompiledModel compiledModel, String inputName, String outputName) {
		this.compiledModel = compiledModel;
		this.inputName = inputName;
		this.outputName = outputName;
	}

	/**
	 * Binds the named input declaration to the given buffer.
	 *
	 * <p>The buffer is re-read on every {@link #tick()} so that updates made by
	 * the caller between ticks flow into the pipeline.</p>
	 *
	 * @param name   the declared input name (must match the PDSL {@code input} declaration)
	 * @param buffer the source buffer; must not be null
	 * @throws IllegalArgumentException if {@code name} does not match the declared input name
	 */
	public void attachInput(String name, PackedCollection<?> buffer) {
		if (inputName != null && !inputName.equals(name)) {
			throw new IllegalArgumentException(
					"Pipeline input name mismatch: declared '" + inputName + "', got '" + name + "'");
		}
		this.inputBuffer = buffer;
	}

	/**
	 * Binds the named output declaration to the given receptor.
	 *
	 * <p>The receptor is invoked on every {@link #tick()} to receive the pipeline output.</p>
	 *
	 * @param name     the declared output name (must match the PDSL {@code output} declaration)
	 * @param receptor the downstream receptor; must not be null
	 * @throws IllegalArgumentException if {@code name} does not match the declared output name
	 */
	public void attachOutput(String name, Receptor<PackedCollection<?>> receptor) {
		if (outputName != null && !outputName.equals(name)) {
			throw new IllegalArgumentException(
					"Pipeline output name mismatch: declared '" + outputName + "', got '" + name + "'");
		}
		this.outputReceptor = receptor;
	}

	/**
	 * Returns one tick of execution for the Temporal path.
	 *
	 * <p>Reads from the attached input buffer, runs the compiled forward pass, and
	 * pushes the result to the attached output receptor. Both must be bound before
	 * this method is called.</p>
	 *
	 * @return a supplier of the tick runnable
	 * @throws IllegalStateException if either attachment is missing
	 */
	@Override
	public Supplier<Runnable> tick() {
		if (inputBuffer == null) {
			throw new IllegalStateException(
					"PdslTemporalBlock '" + inputName + "' input not attached — "
							+ "call attachInput() before adding to CellList");
		}
		if (outputReceptor == null) {
			throw new IllegalStateException(
					"PdslTemporalBlock '" + outputName + "' output not attached — "
							+ "call attachOutput() before adding to CellList");
		}
		PackedCollection<?> src = inputBuffer;
		Receptor<PackedCollection<?>> dest = outputReceptor;
		return () -> () -> {
			PackedCollection<?> result = compiledModel.forward(src);
			dest.push(() -> args -> result).get().run();
		};
	}

	/**
	 * Cell path: runs the compiled forward pass with the upstream {@code protein} as input
	 * and forwards the result to the downstream receptor.
	 *
	 * <p>The protein is evaluated at execution time (inside the returned Runnable), which
	 * is the correct point for a pipeline boundary — all upstream computation has already
	 * completed before this Runnable runs.</p>
	 *
	 * @param protein the upstream input producer
	 * @return a supplier of the push operation
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> protein) {
		if (downstreamReceptor == null) {
			return new OperationList();
		}
		Receptor<PackedCollection<?>> dest = downstreamReceptor;
		return () -> () -> {
			PackedCollection<?> input = protein.get().evaluate();
			PackedCollection<?> result = compiledModel.forward(input);
			dest.push(() -> args -> result).get().run();
		};
	}

	/** {@inheritDoc} */
	@Override
	public void setReceptor(Receptor<PackedCollection<?>> r) {
		this.downstreamReceptor = r;
	}

	/** {@inheritDoc} */
	@Override
	public Receptor<PackedCollection<?>> getReceptor() {
		return downstreamReceptor;
	}

	/** {@inheritDoc} */
	@Override
	public Supplier<Runnable> setup() {
		return new OperationList();
	}
}
