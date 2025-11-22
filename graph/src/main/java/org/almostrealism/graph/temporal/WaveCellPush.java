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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * Computation that reads a sample from wave data and outputs it to the destination.
 *
 * <p>{@code WaveCellPush} extends {@link WaveCellComputation} to implement the
 * sample reading operation for wave cell audio processing. It reads a sample
 * from the wave data at the current frame position, applies amplitude scaling,
 * and writes the result to the output.</p>
 *
 * <p>The computation performs bounds checking and outputs 0 when the wave
 * position is outside the valid range [0, waveCount). The sample value is
 * calculated as:</p>
 * <pre>
 * output = (position >= 0 && position < waveCount)
 *          ? amplitude * wave[waveIndex + floor(position)]
 *          : 0.0
 * </pre>
 *
 * @author Michael Murray
 * @see WaveCellComputation
 * @see WaveCell
 */
public class WaveCellPush extends WaveCellComputation implements ExpressionFeatures {

	/**
	 * Creates a new WaveCellPush computation.
	 *
	 * @param data   the wave cell data containing processing state
	 * @param wave   producer for the source waveform data
	 * @param frame  producer for the current frame position
	 * @param output the destination for the computed sample value
	 */
	public WaveCellPush(WaveCellData data,
						Producer<PackedCollection<?>> wave,
						Producer<PackedCollection<?>> frame,
						PackedCollection<?> output) {
		super(data, wave, frame, output);
	}

	/**
	 * Creates a new WaveCellPush from producer arguments.
	 *
	 * @param arguments the producer arguments
	 */
	private WaveCellPush(Producer... arguments) {
		super(arguments);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Creates a new WaveCellPush instance from the child processes.</p>
	 */
	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new WaveCellPush(children.toArray(Producer[]::new));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Prepares the scope by generating the sample reading expression with
	 * bounds checking and amplitude scaling.</p>
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		Expression<Boolean> condition = getWavePosition().valueAt(0).greaterThanOrEqual(e(0)).and(
				getWavePosition().valueAt(0).lessThan(getWaveCount().valueAt(0)));

		Expression<Double> value = getAmplitude().valueAt(0).multiply(
				getWave().reference(getWaveIndex().valueAt(0).add(getWavePosition().valueAt(0).floor())));
		Expression<?> conditional = conditional(condition, value, e(0.0));
		addVariable(getOutput().reference(e(0)).assign(conditional));
	}
}
