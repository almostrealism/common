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

package org.almostrealism.audio.sources;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

/**
 * Reads a sample from a wavetable using linear interpolation.
 * The current wave position is used to index into the table,
 * with fractional positions interpolated between adjacent samples.
 *
 * @see WavetableCell
 */
public class WavetablePush extends OperationComputationAdapter<PackedCollection> implements ExpressionFeatures {
	private final int tableSize;

	public WavetablePush(SineWaveCellData data, Producer<PackedCollection> envelope,
						 PackedCollection wavetable, int tableSize, PackedCollection output) {
		super(Ops.o().p(output),
				data.getWavePosition(),
				data.getPhase(),
				data.getAmplitude(),
				data.getDepth(),
				envelope,
				Ops.o().p(wavetable));
		this.tableSize = tableSize;
	}

	private ArrayVariable<Double> getOutput() { return getArgument(0); }
	private ArrayVariable<Double> getWavePosition() { return getArgument(1); }
	private ArrayVariable<Double> getPhase() { return getArgument(2); }
	private ArrayVariable<Double> getAmplitude() { return getArgument(3); }
	private ArrayVariable<Double> getDepth() { return getArgument(4); }
	private ArrayVariable<Double> getEnvelope() { return getArgument(5); }
	private ArrayVariable<Double> getWavetable() { return getArgument(6); }

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		purgeVariables();

		Expression<Double> one = e(1.0);

		Expression<? extends Number> t = getWavePosition().valueAt(0).add(getPhase().valueAt(0));
		Expression<? extends Number> frac = t.subtract(t.floor());

		Expression<? extends Number> tablePos = frac.multiply(e(tableSize));
		Expression<? extends Number> tablePosFloor = tablePos.floor();
		Expression<?> index0 = tablePosFloor.imod(new IntegerConstant(tableSize));
		Expression<?> index1 = tablePosFloor.add(one).imod(new IntegerConstant(tableSize));
		Expression<? extends Number> blend = tablePos.subtract(tablePosFloor);

		Expression<? extends Number> sample0 = getWavetable().valueAt(index0);
		Expression<? extends Number> sample1 = getWavetable().valueAt(index1);

		Expression<? extends Number> oneMinusBlend = one.subtract(blend);
		Expression<? extends Number> interpolated = sample0.multiply(oneMinusBlend)
				.add(sample1.multiply(blend));

		Expression<? extends Number> amp = getAmplitude().valueAt(0);
		Expression<? extends Number> env = getEnvelope().valueAt(0);
		Expression<? extends Number> depth = getDepth().valueAt(0);

		addVariable(getOutput().reference(e(0)).assign(
				env.multiply(amp).multiply(interpolated.multiply(depth))
		));
	}
}
