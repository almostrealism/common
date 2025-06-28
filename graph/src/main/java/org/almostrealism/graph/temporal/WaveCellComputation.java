/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.Objects;
import java.util.function.Supplier;

public abstract class WaveCellComputation extends OperationComputationAdapter<PackedCollection<?>> {
	protected HybridScope scope;

	public WaveCellComputation(WaveCellData data, PackedCollection<?> wave, Producer<Scalar> frame, Scalar output) {
		this(data, () -> new Provider<>(wave), frame, output);
	}

	public WaveCellComputation(WaveCellData data, Producer<PackedCollection<?>> wave, Producer<Scalar> frame, Scalar output) {
		super(() -> new Provider<>(output), wave,
				(Supplier) Objects.requireNonNull(frame),
				(Supplier) data.getWaveLength(),
				(Supplier) data.getWaveIndex(),
				(Supplier) data.getWaveCount(),
				(Supplier) data.getAmplitude(),
				(Supplier) data.getDuration());
	}

	protected WaveCellComputation(Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(arguments);
	}

	public ArrayVariable getOutput() { return getArgument(0); }
	public ArrayVariable getWave() { return getArgument(1); }
	public ArrayVariable getWavePosition() { return getArgument(2); }
	public ArrayVariable getWaveLength() { return getArgument(3); }
	public ArrayVariable getWaveIndex() { return getArgument(4); }
	public ArrayVariable getWaveCount() { return getArgument(5); }
	public ArrayVariable getAmplitude() { return getArgument(6); }

	@Deprecated
	public ArrayVariable getDuration() { return getArgument(7); }

	@Override
	public Scope getScope(KernelStructureContext context) { return scope == null ? super.getScope(context) : scope; }
}