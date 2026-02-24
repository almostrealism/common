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

package org.almostrealism.audio.computations;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;
import java.util.function.Consumer;

public class SilenceDurationComputation extends OperationComputationAdapter<PackedCollection> {
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

		String value = getArgument(2).valueAt(0).getSimpleExpression(getLanguage());
		String min = getArgument(1).valueAt(0).getSimpleExpression(getLanguage());
		String duration = getArgument(0).valueAt(0).getSimpleExpression(getLanguage());

		Consumer<String> code = scope.code();
		code.accept("if (" + value + " > " + min + ") " + duration + " = 0;\n");
		code.accept("if (" + value + " <= " + min + ") " + duration + " = " + duration + " + 1;\n");
		return scope;
	}
}
