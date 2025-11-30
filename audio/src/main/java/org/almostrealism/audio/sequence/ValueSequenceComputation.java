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

public abstract class ValueSequenceComputation extends OperationComputationAdapter<PackedCollection> implements CodeFeatures {
	protected HybridScope scope;
	protected final boolean repeat;

	public ValueSequenceComputation(BaseAudioData data,
									Producer<PackedCollection> durationFrames,
									PackedCollection output, boolean repeat,
									Producer<PackedCollection>... choices) {
		super(inputArgs(data, durationFrames, output, choices));
		this.repeat = repeat;
	}

	public ArrayVariable getOutput() { return getArgument(0); }
	public ArrayVariable getWavePosition() { return getArgument(1); }
	public ArrayVariable getWaveLength() { return getArgument(2); }
	public ArrayVariable getDurationFrames() { return getArgument(3); }

	public Producer<PackedCollection> output() { return getInputs().get(0); }
	public Producer<PackedCollection> wavePosition() { return getInputs().get(1); }
	public Producer<PackedCollection> durationFrames() { return getInputs().get(3); }

	public <T> List<T> choices(Function<Producer<PackedCollection>, T> processor) {
		return IntStream.range(4, getInputs().size())
				.mapToObj(i -> (T) processor.apply(getInputs().get(i)))
				.collect(Collectors.toList());
	}

	@Override
	public Scope getScope(KernelStructureContext context) { return scope; }

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
