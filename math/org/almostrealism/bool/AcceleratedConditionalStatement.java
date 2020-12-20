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

package org.almostrealism.bool;

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.MemWrapper;
import io.almostrealism.relation.Producer;
import org.almostrealism.util.Compactable;
import io.almostrealism.relation.Evaluable;

import java.util.List;

public interface AcceleratedConditionalStatement<T extends MemWrapper> extends Evaluable<T>, Producer<T>, Compactable {
	Expression getCondition();

	List<ArrayVariable<? extends MemWrapper>> getArguments();
	List<Variable<?>> getVariables();

	List<ArrayVariable<Scalar>> getOperands();

	ArrayVariable<T> getTrueValue();
	ArrayVariable<T> getFalseValue();

	@Override
	default Evaluable<T> get() {
		return this;
	}
}
