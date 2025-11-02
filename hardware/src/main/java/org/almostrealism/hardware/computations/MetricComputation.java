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

package org.almostrealism.hardware.computations;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.Metric;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.hardware.mem.Bytes;

public class MetricComputation<T> extends OperationComputationAdapter<T> implements ExpressionFeatures {
	private String message;
	private int logFrequency;
	private int pos, memLength;

	public MetricComputation(String message, int logFrequency, Producer<T> measure, int pos, int memLength) {
		super(() -> new Provider(new Bytes(1)), measure);
		this.message = message;
		this.logFrequency = logFrequency;
		this.pos = pos;
		this.memLength = memLength;
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		Scope<Void> scope = super.getScope(context);
		Metric metric = new Metric(getArgument(0).reference(e(0)), logFrequency);
		metric.addMonitoredVariable(message, getArgument(1).reference(e(pos)));
		scope.getMetrics().add(metric);
		return scope;
	}
}
