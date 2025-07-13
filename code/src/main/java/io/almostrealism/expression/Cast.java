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

import io.almostrealism.code.Precision;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class Cast<T> extends UnaryExpression<T> {
	public static final String FP_NAME = "double";
	public static final String INT_NAME = "int";
	public static final String LONG_NAME = "long";

	private String typeName;

	protected Cast(Class<T> type, String typeName, Expression<?> operand) {
		super(type, "(" + typeName + ")", operand);
		this.typeName = typeName;

		if (typeName == null) {
			throw new IllegalArgumentException();
		}
	}

	public String getTypeName() { return typeName; }

	@Override
	protected String getOperator(LanguageOperations lang) {
		if (FP_NAME.equals(getTypeName()) && lang.getPrecision() != Precision.FP64) {
			return "(float)";
		}

		return "(" + getTypeName() + ")";
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
	public Expression<T> recreate(List children) {
		if (children.size() != 1) throw new UnsupportedOperationException();
		return Cast.of(getType(), typeName, (Expression) children.get(0));
	}

	public static <T> Expression<T> of(Class<T> type, String typeName, Expression<?> value) {
		OptionalDouble d = value.doubleValue();

		if (d.isPresent()) {
			switch (typeName) {
				case Cast.FP_NAME:
					return (Expression) new DoubleConstant(d.getAsDouble());
				case Cast.LONG_NAME:
					if (d.getAsDouble() > Long.MAX_VALUE || d.getAsDouble() < Long.MIN_VALUE) {
						throw new ArithmeticException(String.valueOf(d.getAsDouble()));
					}

					return (Expression) new LongConstant((long) d.getAsDouble());
				case Cast.INT_NAME:
					if (d.getAsDouble() > Integer.MAX_VALUE || d.getAsDouble() < Integer.MIN_VALUE) {
						throw new ArithmeticException(String.valueOf(d.getAsDouble()));
					}

					return (Expression) new IntegerConstant((int) d.getAsDouble());
			}
		}

		if (value instanceof Cast) {
			return Cast.of(type, typeName, value.getChildren().get(0));
		}

		return new Cast<>(type, typeName, value);
	}
}
