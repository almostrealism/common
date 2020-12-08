/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.InstanceReference;
import io.almostrealism.code.expressions.Sum;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.DynamicAcceleratedOperationAdapter;
import org.almostrealism.relation.Evaluable;
import org.almostrealism.relation.ScopeInputManager;
import org.almostrealism.time.CursorPair;

import static org.almostrealism.util.Ops.*;

import java.util.function.Supplier;

public class SummationCellOperation extends DynamicAcceleratedOperationAdapter<Scalar> {
	private boolean prepared;

	public SummationCellOperation(SummationCell cell, Supplier<Evaluable<? extends Scalar>> protein) {
		super(ops().p(cell.getCachedValue()), protein);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		if (prepared) return;
		addVariable(getArgument(0).get(0).assign(new Sum(getArgument(0).get(0), getArgument(1).get(0))));

		prepared = true;
	}
}
