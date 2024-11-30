/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.Computation;
import io.almostrealism.scope.Cases;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;
import java.util.stream.IntStream;

public class Switch extends OperationComputationAdapter<PackedCollection<?>> implements ExpressionFeatures {
	private final List<Computation> choices;

	public Switch(Producer<PackedCollection<?>> decision, List<Computation> choices) {
		super(new Producer[] { decision });
		this.choices = choices;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		choices.forEach(c -> c.prepareArguments(map));
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		choices.forEach(c -> c.prepareScope(manager, context));
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		Cases<Void> scope = new Cases<>(getName(), getMetadata());

		double interval = 1.0 / choices.size();

		ArrayVariable<?> decisionValue = getArgument(0, 2);

		IntStream.range(0, choices.size()).forEach(i -> {
			double val = (i + 1) * interval;
			scope.getConditions().add(decisionValue.valueAt(0).lessThanOrEqual(e(val)));
			scope.getChildren().add(choices.get(i).getScope(context));
		});

		return scope;
	}
}
