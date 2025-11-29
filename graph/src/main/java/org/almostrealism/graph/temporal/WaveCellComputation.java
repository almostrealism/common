/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.graph.temporal;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.Objects;

/**
 * Abstract base class for wave cell computation operations.
 *
 * <p>{@code WaveCellComputation} extends {@link OperationComputationAdapter} to
 * provide the foundation for hardware-accelerated wave processing operations.
 * It manages the argument layout for wave cell computations, organizing the
 * inputs required for sample-based audio processing.</p>
 *
 * <p>Argument layout (indices 0-6):</p>
 * <ul>
 *   <li>0: Output - destination for computed sample values</li>
 *   <li>1: Wave - the source waveform data</li>
 *   <li>2: Wave Position (frame) - current playback position</li>
 *   <li>3: Wave Length - size of the waveform</li>
 *   <li>4: Wave Index - starting offset in the wave data</li>
 *   <li>5: Wave Count - number of samples to process</li>
 *   <li>6: Amplitude - volume multiplier</li>
 * </ul>
 *
 * @author Michael Murray
 * @see OperationComputationAdapter
 * @see WaveCellPush
 * @see WaveCellData
 */
public abstract class WaveCellComputation extends OperationComputationAdapter<PackedCollection> {
	/** The hybrid scope for this computation, if custom scope generation is used. */
	protected HybridScope scope;

	/**
	 * Creates a new wave cell computation with the specified parameters.
	 *
	 * @param data   the wave cell data containing processing state
	 * @param wave   the source waveform data
	 * @param frame  producer for the current frame position
	 * @param output the destination for computed values
	 */
	public WaveCellComputation(WaveCellData data,
							   PackedCollection wave,
							   Producer<PackedCollection> frame,
							   PackedCollection output) {
		this(data, () -> new Provider<>(wave), frame, output);
	}

	/**
	 * Creates a new wave cell computation with producer-based wave data.
	 *
	 * @param data   the wave cell data containing processing state
	 * @param wave   producer for the source waveform data
	 * @param frame  producer for the current frame position (must not be null)
	 * @param output the destination for computed values
	 * @throws NullPointerException if frame is null
	 */
	public WaveCellComputation(WaveCellData data,
							   Producer<PackedCollection> wave,
							   Producer<PackedCollection> frame,
							   PackedCollection output) {
		super(() -> new Provider<>(output), wave,
				Objects.requireNonNull(frame),
				data.getWaveLength(),
				data.getWaveIndex(),
				data.getWaveCount(),
				data.getAmplitude());
	}

	/**
	 * Creates a new wave cell computation from an array of producer arguments.
	 *
	 * @param arguments the producer arguments in the standard layout
	 */
	protected WaveCellComputation(Producer... arguments) {
		super(arguments);
	}

	/**
	 * Returns the output destination variable.
	 *
	 * @return the output array variable at index 0
	 */
	public ArrayVariable getOutput() { return getArgument(0); }

	/**
	 * Returns the source waveform variable.
	 *
	 * @return the wave array variable at index 1
	 */
	public ArrayVariable getWave() { return getArgument(1); }

	/**
	 * Returns the wave position (frame) variable.
	 *
	 * @return the wave position array variable at index 2
	 */
	public ArrayVariable getWavePosition() { return getArgument(2); }

	/**
	 * Returns the wave length variable.
	 *
	 * @return the wave length array variable at index 3
	 */
	public ArrayVariable getWaveLength() { return getArgument(3); }

	/**
	 * Returns the wave index (starting offset) variable.
	 *
	 * @return the wave index array variable at index 4
	 */
	public ArrayVariable getWaveIndex() { return getArgument(4); }

	/**
	 * Returns the wave count (number of samples) variable.
	 *
	 * @return the wave count array variable at index 5
	 */
	public ArrayVariable getWaveCount() { return getArgument(5); }

	/**
	 * Returns the amplitude variable.
	 *
	 * @return the amplitude array variable at index 6
	 */
	public ArrayVariable getAmplitude() { return getArgument(6); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the custom scope if set, otherwise delegates to the parent implementation.</p>
	 */
	@Override
	public Scope getScope(KernelStructureContext context) { return scope == null ? super.getScope(context) : scope; }
}