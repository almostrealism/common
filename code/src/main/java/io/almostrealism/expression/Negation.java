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

import io.almostrealism.kernel.KernelSeriesProvider;

import java.util.List;
import java.util.Optional;

public class Negation extends UnaryExpression<Boolean> {
	public Negation(Expression<Boolean> value) {
		super(Boolean.class, "!", value);
	}

	@Override
	protected boolean isIncludeSpace() { return false; }

	@Override
	public Optional<Boolean> booleanValue() {
		Optional<Boolean> value = getChildren().get(0).booleanValue();
		if (value.isEmpty()) return value;
		return Optional.of(!value.get());
	}

	@Override
	public Expression<Boolean> generate(List<Expression<?>> children) {
		return new Negation((Expression<Boolean>) children.get(0));
	}

	@Override
	public Expression<Boolean> simplify(KernelSeriesProvider provider) {
		Expression<Boolean> e = super.simplify(provider);
		if (!(e instanceof Negation)) return e;
		
		Optional<Boolean> c = e.getChildren().get(0).booleanValue();
		return c.isPresent() ? new BooleanConstant(!c.get()) : e;
	}
}
