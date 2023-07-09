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

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Cast extends UnaryExpression<Double> {
	private String typeName;

	public Cast(String typeName, Expression<?> operand) {
		super(Double.class, "(" + typeName + ")", operand);
		this.typeName = typeName;
	}

	public String getTypeName() {
		return typeName;
	}

	@Override
	public Expression<Double> simplify() {
		Expression<Double> flat = super.simplify();
		if (!(flat instanceof Cast)) return flat;

		OptionalDouble d = flat.getChildren().get(0).doubleValue();
		if (d.isPresent() && typeName.equals("int"))
			return (Expression) new IntegerConstant((int) d.getAsDouble());

		if (flat.getChildren().get(0) instanceof Cast) {
			return new Cast(typeName, flat.getChildren().get(0).getChildren().get(0));
		}

		return flat;
	}

	@Override
	public Expression generate(List children) {
		if (children.size() != 1) throw new UnsupportedOperationException();
		return new Cast(typeName, (Expression) children.get(0));
	}

	@Override
	public Cast toInt() {
		if (typeName.equals("int")) return this;
		return super.toInt();
	}

	@Override
	public OptionalInt intValue() {
		OptionalInt i = getChildren().get(0).intValue();
		if (i.isPresent()) return i;
		if (typeName.equals("int")) {
			OptionalDouble d = getChildren().get(0).doubleValue();
			if (d.isPresent()) return OptionalInt.of((int) d.getAsDouble());
		}
		return super.intValue();
	}

	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble d = getChildren().get(0).doubleValue();
		if (d.isPresent()) return d;
		return super.doubleValue();
	}

	@Override
	public Number kernelValue(int kernelIndex) {
		double v = getChildren().get(0).kernelValue(kernelIndex).doubleValue();

		if (typeName.equals("int")) {
			return Integer.valueOf((int) v);
		} else {
			return Double.valueOf(v);
		}
	}

	@Override
	public String toString() {
		return getExpression();
	}
}
