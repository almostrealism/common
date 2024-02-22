/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalInt;

public class And extends BinaryExpression<Integer> {

	public And(Expression<Integer> a, Expression<Integer> b) {
		super(Integer.class, a, b);

		if (a.getType() != Integer.class || b.getType() != Integer.class)
			throw new UnsupportedOperationException();
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return  getChildren().get(0).getWrappedExpression(lang) + " & " +
					getChildren().get(1).getWrappedExpression(lang);
	}

	@Override
	public OptionalInt intValue() {
		Expression a = getChildren().get(0);
		Expression b = getChildren().get(1);

		if (a.intValue().isPresent() && b.intValue().isPresent()) {
			return OptionalInt.of(a.intValue().getAsInt() & b.intValue().getAsInt());
		}

		return super.intValue();
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		return getChildren().get(1).upperBound(context);
	}

	@Override
	public Expression<Integer> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new And((Expression) children.get(0), (Expression) children.get(1));
	}

	@Override
	public Expression simplify(KernelStructureContext context) {
		Expression<?> flat = super.simplify(context);
		return flat;
	}

	@Override
	public boolean isKernelValue(IndexValues values) {
		return getChildren().get(0).isKernelValue(values) && getChildren().get(1).isKernelValue(values);
	}

	@Override
	public Number value(IndexValues indexValues) {
		return getChildren().get(0).value(indexValues).intValue() & getChildren().get(1).value(indexValues).intValue();
	}

	@Override
	public Number evaluate(Number... children) {
		return children[0].intValue() & children[1].intValue();
	}
}
