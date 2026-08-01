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

/**
 * An expression that yields the size of an {@link ArrayVariable}.
 *
 * <p>Renders to the language-specific variable size name via
 * {@link io.almostrealism.lang.LanguageOperations#getVariableSizeName}.
 * Assignment is not supported since the size is computed by the runtime.</p>
 */
public class SizeValue extends StaticReference<Integer> {

	/**
	 * Constructs a size-value expression for the given array variable.
	 *
	 * @param referent the array variable whose size this expression represents
	 */
	public SizeValue(ArrayVariable<?> referent) {
		super(Integer.class, null, referent);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.getVariableSizeName((ArrayVariable) getReferent());
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
