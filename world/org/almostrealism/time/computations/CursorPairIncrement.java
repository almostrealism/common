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

package org.almostrealism.time.computations;

import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.InstanceReference;
import io.almostrealism.code.expressions.Sum;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicAcceleratedOperationAdapter;
import org.almostrealism.relation.Evaluable;
import org.almostrealism.time.CursorPair;

import java.util.function.Supplier;

public class CursorPairIncrement extends DynamicAcceleratedOperationAdapter {
	public CursorPairIncrement(Supplier<Evaluable<? extends CursorPair>> cursors,
							   Supplier<Evaluable<? extends Scalar>> increment) {
		super(new Supplier[] { cursors, increment });
	}

	@Override
	public void init() {
		super.init();
		addVariable(new Variable(getArgumentValueName(0, 0), false,
				new Sum(new InstanceReference<>(new Variable<>(getArgumentValueName(0, 0), false, getArgument(0))),
						new InstanceReference(new Variable<>(getArgumentValueName(1, 0), false, getArgument(1))))));
		addVariable(new Variable(getArgumentValueName(0, 1), false,
				new Sum(new InstanceReference(new Variable<>(getArgumentValueName(0, 1), false, getArgument(0))),
						new InstanceReference(new Variable<>(getArgumentValueName(1, 0), false, getArgument(1))))));
	}
}
