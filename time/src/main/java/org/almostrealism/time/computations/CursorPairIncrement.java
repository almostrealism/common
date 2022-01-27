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

package org.almostrealism.time.computations;

import io.almostrealism.expression.Sum;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.code.ScopeInputManager;
import org.almostrealism.time.CursorPair;

import java.util.function.Supplier;

public class CursorPairIncrement extends DynamicOperationComputationAdapter {
	private boolean prepared = false;

	public CursorPairIncrement(Supplier<Evaluable<? extends CursorPair>> cursors,
							   Supplier<Evaluable<? extends Scalar>> increment) {
		super(new Supplier[] { cursors, increment });
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		if (prepared) return;

		if (getArgument(1).isStatic()) {
			addVariable(getArgument(0).valueAt(0).assign(new Sum(getArgument(0).valueAt(0), getInputValue(1, 0))));
			addVariable(getArgument(0).valueAt(1).assign(new Sum(getArgument(0).valueAt(1), getInputValue(1, 0))));
		} else {
			addVariable(getArgument(0).valueAt(0).assign(new Sum(getArgument(0).valueAt(0), getArgument(1).valueAt(0))));
			addVariable(getArgument(0).valueAt(1).assign(new Sum(getArgument(0).valueAt(1), getArgument(1).valueAt(0))));
		}

		prepared = true;
	}
}
