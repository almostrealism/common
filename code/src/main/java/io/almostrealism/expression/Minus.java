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

import io.almostrealism.kernel.KernelStructureContext;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class Minus<T extends Number> extends UnaryExpression<T> {
	public Minus(Expression<? extends Number> value) {
		super((Class) value.getType(), "-", value);
	}

	@Override
	public OptionalInt intValue() {
		OptionalInt i = getChildren().get(0).intValue();
		if (i.isPresent()) return OptionalInt.of(i.getAsInt() * -1);
		return super.intValue();
	}

	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble d = getChildren().get(0).doubleValue();
		if (d.isPresent()) return OptionalDouble.of(d.getAsDouble() * -1);
		return super.doubleValue();
	}

	@Override
	public boolean isKernelValue(IndexValues values) {
		return getChildren().get(0).isKernelValue(values);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong i = getChildren().get(0).upperBound(context);
		if (i.isPresent()) {
			long value = i.getAsLong();
			if (value > 0) return OptionalLong.of(0);
			return OptionalLong.of(-value);
		}

		return super.upperBound(context);
	}

	@Override
	public Number value(IndexValues indexValues) {
		Number v = getChildren().get(0).value(indexValues);
		if (v instanceof Integer) return -1 * (Integer) v;
		return -1.0 * (Double) v;
	}

	@Override
	public Number evaluate(Number... children) {
		return -1 * children[0].doubleValue();
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		if (children.size() != 1)  throw new UnsupportedOperationException();
		return new Minus(children.get(0));
	}
}
