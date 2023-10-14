/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.expression.Expression;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

public class DefaultNameProvider implements NameProvider {
	private String function;

	public DefaultNameProvider(String function) {
		this.function = function;
	}

	@Override
	public String getFunctionName() {
		return function;
	}

	@Override
	public Variable getOutputVariable() { return getArgument(0); }

	@Override
	public String getVariableDimName(ArrayVariable v, int dim) {
		return "1";
	}

	@Override
	public String getVariableSizeName(ArrayVariable v) {
		return "1";
	}

	@Override
	public Expression<?> getArrayPosition(ArrayVariable v, Expression<?> pos, int kernelIndex) {
		throw new UnsupportedOperationException();
	}
}
