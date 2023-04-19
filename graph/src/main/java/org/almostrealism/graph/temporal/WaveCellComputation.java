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

package org.almostrealism.graph.temporal;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.HybridScope;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;

import java.util.Objects;
import java.util.function.Supplier;

public abstract class WaveCellComputation extends DynamicOperationComputationAdapter {
	protected HybridScope scope;

	public WaveCellComputation(WaveCellData data, PackedCollection<?> wave, Producer<Scalar> frame, Scalar output) {
		super(() -> new Provider<>(output),
				() -> new Provider<>(wave),
				(Supplier) Objects.requireNonNull(frame),
				(Supplier) data.getWaveLength(),
				(Supplier) data.getWaveIndex(),
				(Supplier) data.getWaveCount(),
				(Supplier) data.getAmplitude(),
				(Supplier) data.getDuration());
	}

	public ArrayVariable getOutput() { return getArgument(0, 2); }
	public ArrayVariable getWave() { return getArgument(1); }
	public ArrayVariable getWavePosition() { return getArgument(2, 2); }
	public ArrayVariable getWaveLength() { return getArgument(3, 2); }
	public ArrayVariable getWaveIndex() { return getArgument(4, 2); }
	public ArrayVariable getWaveCount() { return getArgument(5, 2); }
	public ArrayVariable getAmplitude() { return getArgument(6, 2); }

	@Deprecated
	public ArrayVariable getDuration() { return getArgument(7, 2); }

	@Override
	public Scope getScope() { return scope; }
}