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

import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class Cast<T> extends UnaryExpression<T> {
	private String typeName;

	public Cast(Class<T> type, String typeName, Expression<?> operand) {
		super(type, "(" + typeName + ")", operand);
		this.typeName = typeName;

		if (typeName == null) {
			throw new IllegalArgumentException();
		}
	}

	public String getTypeName() {
		return typeName;
	}

	@Override
	public OptionalInt intValue() {
		OptionalInt i = getChildren().get(0).intValue();
		if (i.isPresent()) return i;
		if (getType() == Integer.class) {
			OptionalDouble d = getChildren().get(0).doubleValue();
			if (d.isPresent()) return OptionalInt.of((int) d.getAsDouble());
		}
		return super.intValue();
	}

	@Override
	public OptionalLong longValue() {
		OptionalLong l = super.longValue();
		if (l.isPresent()) return l;

		if (getType() == Long.class) {
			OptionalDouble d = getChildren().get(0).doubleValue();
			if (d.isPresent()) return OptionalLong.of((long) d.getAsDouble());
		}

		return l;
	}

	@Override
	public OptionalDouble doubleValue() {
		OptionalLong l = longValue();
		if (l.isPresent()) return OptionalDouble.of(l.getAsLong());

		if (getType() == Double.class) {
			OptionalDouble d = getChildren().get(0).doubleValue();
			if (d.isPresent()) return d;
		}

		return super.doubleValue();
	}

	@Override
	public boolean isValue(IndexValues values) {
		return getChildren().get(0).isValue(values);
	}

	@Override
	public KernelSeries kernelSeries() {
		return getChildren().get(0).kernelSeries();
	}

	@Override
	public Number value(IndexValues indexValues) {
		double v = getChildren().get(0).value(indexValues).doubleValue();

		if (typeName.equals("int")) {
			return Integer.valueOf((int) v);
		} else {
			return Double.valueOf(v);
		}
	}

	@Override
	public Number evaluate(Number... children) {
		if (typeName.equals("int")) {
			return Integer.valueOf(children[0].intValue());
		} else {
			return Double.valueOf(children[0].doubleValue());
		}
	}

	@Override
	public Expression<T> simplify(KernelStructureContext context, int depth) {
		Expression<T> flat = super.simplify(context, depth);
		if (!(flat instanceof Cast)) return flat;

		OptionalDouble d = flat.getChildren().get(0).doubleValue();
		if (d.isPresent() && typeName.equals("int"))
			return (Expression) new IntegerConstant((int) d.getAsDouble());

		if (flat.getChildren().get(0) instanceof Cast) {
			return new Cast(getType(), typeName, flat.getChildren().get(0).getChildren().get(0));
		}

		return flat;
	}

	@Override
	public Expression<T> generate(List children) {
		if (children.size() != 1) throw new UnsupportedOperationException();
		return new Cast<>(getType(), typeName, (Expression) children.get(0));
	}
}
