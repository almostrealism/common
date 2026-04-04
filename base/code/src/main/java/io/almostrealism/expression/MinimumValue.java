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

package io.almostrealism.expression;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.Precision;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.OptionalLong;

/**
 * A special expression representing the smallest representable finite floating-point value
 * for the current precision.
 *
 * <p>Renders to the language-specific literal for the minimum floating-point value
 * and always evaluates to {@link io.almostrealism.code.Precision#FP64} minimum during
 * numeric evaluation. The upper bound is reported as zero since the value is negative.</p>
 */
public class MinimumValue extends StaticReference<Double> {

	/**
	 * Constructs a minimum-value expression.
	 * The reference name is {@code null}; the rendered value is determined by
	 * {@link io.almostrealism.lang.LanguageOperations#getPrecision()} at code-generation time.
	 */
	public MinimumValue() {
		super(Double.class, null);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		return OptionalLong.of(0);
	}

	@Override
	public Number evaluate(Number... children) {
		return Precision.FP64.minValue();
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.getPrecision().stringForDouble(lang.getPrecision().minValue());
	}

	@Override
	public ExpressionAssignment<Double> assign(Expression exp) {
		throw new UnsupportedOperationException();
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return constantZero(target.getShape());
	}
}
