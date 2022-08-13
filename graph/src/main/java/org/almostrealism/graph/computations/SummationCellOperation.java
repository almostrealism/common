/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.graph.computations;

import io.almostrealism.expression.Sum;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import io.almostrealism.code.ScopeInputManager;

import java.util.function.Supplier;

public class SummationCellOperation extends DynamicOperationComputationAdapter<PackedCollection<?>> {
	private boolean prepared;

	public SummationCellOperation(SummationCell cell, Supplier<Evaluable<? extends PackedCollection<?>>> protein) {
		super(() -> new Provider<>(cell.getCachedValue()), protein);
		getMetadata().setShortDescription("SummationCell Push");
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		if (prepared) return;
		addVariable(getArgument(0).valueAt(0).assign(new Sum(getArgument(0).valueAt(0), getArgument(1).valueAt(0))));

		prepared = true;
	}
}
