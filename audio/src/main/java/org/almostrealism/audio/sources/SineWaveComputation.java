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

import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

public abstract class SineWaveComputation extends OperationComputationAdapter<PackedCollection> {
	protected static final double TWO_PI = 2 * Math.PI;

	public SineWaveComputation(SineWaveCellData data, Producer<PackedCollection> envelope, PackedCollection output) {
		super(Ops.o().p(output),
				data.getWavePosition(),
				data.getWaveLength(),
				data.getNotePosition(),
				data.getNoteLength(),
				data.getPhase(),
				data.getAmplitude(),
				data.getDepth(),
				envelope);
	}

	public ArrayVariable<Double> getOutput() { return getArgument(0); }
	public ArrayVariable<Double> getWavePosition() { return getArgument(1); }
	public ArrayVariable<Double> getWaveLength() { return getArgument(2); }
	public ArrayVariable<Double> getNotePosition() { return getArgument(3); }
	public ArrayVariable<Double> getNoteLength() { return getArgument(4); }
	public ArrayVariable<Double> getPhase() { return getArgument(5); }
	public ArrayVariable<Double> getAmplitude() { return getArgument(6); }
	public ArrayVariable<Double> getDepth() { return getArgument(7); }
	public ArrayVariable<Double> getEnvelope() { return getArgument(8); }

	public InstanceReference<ArrayVariable<Double>, Double> output() { return (InstanceReference<ArrayVariable<Double>, Double>) getOutput().valueAt(0); }
	public InstanceReference<ArrayVariable<Double>, Double> wavePosition() { return (InstanceReference<ArrayVariable<Double>, Double>) getWavePosition().valueAt(0); }
	public InstanceReference<ArrayVariable<Double>, Double> waveLength() { return (InstanceReference<ArrayVariable<Double>, Double>) getWaveLength().valueAt(0); }
	public InstanceReference<ArrayVariable<Double>, Double> notePosition() { return (InstanceReference<ArrayVariable<Double>, Double>) getNotePosition().valueAt(0); }
	public InstanceReference<ArrayVariable<Double>, Double> noteLength() { return (InstanceReference<ArrayVariable<Double>, Double>) getNoteLength().valueAt(0); }
	public InstanceReference<ArrayVariable<Double>, Double> phase() { return (InstanceReference<ArrayVariable<Double>, Double>) getPhase().valueAt(0); }
	public InstanceReference<ArrayVariable<Double>, Double> amplitude() { return (InstanceReference<ArrayVariable<Double>, Double>) getAmplitude().valueAt(0); }
	public InstanceReference<ArrayVariable<Double>, Double> depth() { return (InstanceReference<ArrayVariable<Double>, Double>) getDepth().valueAt(0); }
	public InstanceReference<ArrayVariable<Double>, Double> envelope() { return (InstanceReference<ArrayVariable<Double>, Double>) getEnvelope().valueAt(0); }
}
