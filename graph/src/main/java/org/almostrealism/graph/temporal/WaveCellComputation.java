/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.HybridScope;
import io.almostrealism.code.Scope;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;

public abstract class WaveCellComputation extends DynamicOperationComputationAdapter {
	protected HybridScope scope;
	protected final boolean repeat;

	public WaveCellComputation(WaveCellData data, ScalarBank wave, Scalar output, boolean repeat) {
		super(() -> new Provider<>(output),
				() -> new Provider<>(wave),
				data::getWavePosition,
				data::getWaveLength,
				data::getWaveCount,
				data::getAmplitude,
				data::getDuration);
		this.repeat = repeat;
	}

	public ArrayVariable getOutput() { return getArgument(0, 2); }
	public ArrayVariable getWave() { return getArgument(1); }
	public ArrayVariable getWavePosition() { return getArgument(2, 2); }
	public ArrayVariable getWaveLength() { return getArgument(3, 2); }
	public ArrayVariable getWaveCount() { return getArgument(4, 2); }
	public ArrayVariable getAmplitude() { return getArgument(5, 2); }
	public ArrayVariable getDuration() { return getArgument(6, 2); }

	@Override
	public Scope getScope() { return scope; }
}