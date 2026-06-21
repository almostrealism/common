/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.studio.computations;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;

/**
 * GPU-compatible operation that tracks the duration of consecutive silence in an audio
 * stream. The silence counter is incremented when the sample amplitude falls at or below
 * the configured minimum value, and reset to zero when the sample is audible.
 */
public class SilenceDurationComputation extends OperationComputationAdapter<PackedCollection> implements ExpressionFeatures {
	/**
	 * Creates a silence duration computation.
	 *
	 * @param silenceDuration running counter of consecutive silent frames (single element)
	 * @param silenceSettings collection providing the silence threshold
	 *                        (element 0 = minimum audible amplitude)
	 * @param value           the audio sample value to evaluate
	 */
	public SilenceDurationComputation(Producer<PackedCollection> silenceDuration,
									  Producer<PackedCollection> silenceSettings,
									  Producer<PackedCollection> value) {
		super(silenceDuration, silenceSettings, value);
	}

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new SilenceDurationComputation(
				(Producer) children.get(0),
				(Producer) children.get(1),
				(Producer) children.get(2));
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		HybridScope<Void> scope = new HybridScope<>(this);

		Expression<Double> value = getArgument(2).valueAt(0);
		Expression<Double> min = getArgument(1).valueAt(0);
		Expression<Double> duration = getArgument(0).valueAt(0);

		// duration = (value > min) ? 0 : duration + 1
		scope.assign(getArgument(0).valueAt(0),
				conditional(value.greaterThan(min), e(0.0), duration.add(e(1.0))));
		return scope;
	}
}
