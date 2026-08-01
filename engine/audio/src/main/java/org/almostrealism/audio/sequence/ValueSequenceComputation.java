/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.sequence;

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.BaseAudioData;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Base class for computations that manage value sequences in the audio framework.
 * Provides common infrastructure for tracking wave position, duration, and selecting
 * from multiple value choices based on temporal progression. Subclasses implement
 * specific behaviors for pushing values or advancing the sequence.
 *
 * @see ValueSequencePush
 * @see ValueSequenceTick
 */
public abstract class ValueSequenceComputation extends OperationComputationAdapter<PackedCollection> implements CodeFeatures {
	/** Generated kernel scope; populated by subclasses in their constructors. */
	protected HybridScope scope;

	/** When true, the sequence wraps around to the beginning after the last step. */
	protected final boolean repeat;

	/**
	 * Creates a ValueSequenceComputation with the given audio state, duration, output buffer, and choices.
	 *
	 * @param data           audio data providing wave position and wave length state
	 * @param durationFrames duration of each step in frames
	 * @param output         output buffer that receives the selected value
	 * @param repeat         whether the sequence repeats after the last step
	 * @param choices        value producers, one per step in the sequence
	 */
	public ValueSequenceComputation(BaseAudioData data,
									Producer<PackedCollection> durationFrames,
									PackedCollection output, boolean repeat,
									Producer<PackedCollection>... choices) {
		super(inputArgs(data, durationFrames, output, choices));
		this.repeat = repeat;
	}

	/** Returns the kernel variable for the output buffer (argument index 0). */
	public ArrayVariable getOutput() { return getArgument(0); }

	/** Returns the kernel variable for the current wave position (argument index 1). */
	public ArrayVariable getWavePosition() { return getArgument(1); }

	/** Returns the kernel variable for the step wave length (argument index 2). */
	public ArrayVariable getWaveLength() { return getArgument(2); }

	/** Returns the kernel variable for the step duration in frames (argument index 3). */
	public ArrayVariable getDurationFrames() { return getArgument(3); }

	/** Returns the producer for the output buffer. */
	public Producer<PackedCollection> output() { return getInputs().get(0); }

	/** Returns the producer for the current wave position. */
	public Producer<PackedCollection> wavePosition() { return getInputs().get(1); }

	/** Returns the producer for the step duration in frames. */
	public Producer<PackedCollection> durationFrames() { return getInputs().get(3); }

	/**
	 * Returns a transformed list of the value choice producers starting at input index 4.
	 *
	 * @param processor function to apply to each choice producer
	 * @param <T>       the return type of the processor
	 * @return list of processed choice values
	 */
	public <T> List<T> choices(Function<Producer<PackedCollection>, T> processor) {
		return IntStream.range(4, getInputs().size())
				.mapToObj(i -> (T) processor.apply(getInputs().get(i)))
				.collect(Collectors.toList());
	}

	@Override
	public Scope getScope(KernelStructureContext context) { return scope; }

	/**
	 * Assembles the input argument array for the parent OperationComputationAdapter.
	 * The order is: output, wavePosition, waveLength, durationFrames, then all choices.
	 *
	 * @param data           audio data providing wave position and wave length producers
	 * @param durationFrames the duration of each step in frames
	 * @param output         the output buffer that receives the selected value
	 * @param choices        value producers, one per step in the sequence
	 * @return argument array with output at index 0, wave state at 1-2, duration at 3,
	 *         and choices starting at index 4
	 */
	private static Producer[] inputArgs(BaseAudioData data, Producer<PackedCollection> durationFrames,
										PackedCollection output, Producer<PackedCollection>... choices) {
		Producer[] args = new Producer[4 + choices.length];
		args[0] = () -> new Provider<>(output);
		args[1] = data.getWavePosition();
		args[2] = data.getWaveLength();
		args[3] = durationFrames;
		IntStream.range(0, choices.length).forEach(i -> args[i + 4] = choices[i]);
		return args;
	}
}
