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

package io.almostrealism.expression;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;

import java.util.OptionalLong;

public class DimValue extends StaticReference<Integer> {
	private int dim;

	public DimValue(ArrayVariable<?> referent, int dim) {
		super(Integer.class, null, referent);
		this.dim = dim;
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.getVariableDimName((ArrayVariable) getReferent(), dim);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		// TODO  This value can probably be known
		return OptionalLong.empty();
	}

	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		return OptionalLong.of(0);
	}

	@Override
	public ExpressionAssignment<Integer> assign(Expression exp) {
		throw new UnsupportedOperationException();
	}

}
