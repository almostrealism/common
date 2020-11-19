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

package io.almostrealism.code;

import org.almostrealism.relation.NameProvider;

import java.util.function.IntFunction;

public interface MultiExpression<T> extends NameProvider {

	default IntFunction<Variable<T>> getAssignmentFunction(Variable<?> outputVariable) {
		return i -> new Variable<>(getVariableValueName(outputVariable, i, true), false, getValue(i), outputVariable);
	}

	Expression<T> getValue(int pos);
}
