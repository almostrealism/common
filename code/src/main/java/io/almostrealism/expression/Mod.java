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
import java.util.OptionalInt;

public class Mod extends Expression<Double> {
	public static boolean enableSimplification = true;
	public static boolean enableIntegerSimplification = true;
	public static boolean enableFpSimplification = true;

	private boolean fp;

	public Mod(Expression<Double> a, Expression<Double> b) {
		this(a, b, true);
	}

	public Mod(Expression<Double> a, Expression<Double> b, boolean fp) {
		super(Double.class,
				a, b);
		this.fp = fp;

		if (b.intValue().isPresent() && b.intValue().getAsInt() == 0) {
			System.out.println("WARN: Module zero encountered while creating expression - " + getExpression());
		}
	}

	@Override
	public String getExpression() {
		return fp ? "fmod(" + getChildren().get(0).getExpression() + ", " + getChildren().get(1).getExpression() + ")" :
				"(" + getChildren().get(0).getExpression() + ") % (" + getChildren().get(1).getExpression() + ")";
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new Mod((Expression<Double>) children.get(0), (Expression<Double>) children.get(1), fp);
	}

	@Override
	public OptionalInt intValue() {
		Expression input = getChildren().get(0);
		Expression mod = getChildren().get(1);

		if (input.intValue().isPresent() && mod.intValue().isPresent() && mod.intValue().getAsInt() != 0) {
			return OptionalInt.of(input.intValue().getAsInt() % mod.intValue().getAsInt());
		}

		return super.intValue();
	}

	@Override
	public Expression<Double> simplify() {
		Expression<?> flat = super.simplify();
		if (!enableSimplification) return (Expression<Double>) flat;
		if (!(flat instanceof Mod)) return (Expression<Double>) flat;

		Expression input = flat.getChildren().get(0);
		Expression mod = flat.getChildren().get(1);

		if (enableIntegerSimplification && input.intValue().isPresent()) {
			if (input.intValue().getAsInt() == 0) {
				return (Expression) new IntegerConstant(0);
			} else if (mod.intValue().isPresent() && !fp) {
				if (mod.intValue().getAsInt() == 1) {
					return input;
				} else if (mod.intValue().getAsInt() != 0) {
					return (Expression) new IntegerConstant(input.intValue().getAsInt() % mod.intValue().getAsInt());
				} else {
					System.out.println("WARN: Module zero encountered while simplifying expression - " + getExpression());
				}
			}
		} else if (enableIntegerSimplification && mod.intValue().isPresent()) {
			if (mod.intValue().getAsInt() == 1) {
				return (Expression) new IntegerConstant(0);
			}
		} else if (enableFpSimplification && input.doubleValue().isPresent()) {
			if (input.doubleValue().getAsDouble() == 0.0) {
				return new DoubleConstant(0.0);
			} else if (mod.doubleValue().isPresent() && !fp) {
				return new DoubleConstant(input.doubleValue().getAsDouble() % mod.doubleValue().getAsDouble());
			}
		} else if (enableFpSimplification && mod.doubleValue().isPresent()) {
			if (mod.doubleValue().getAsDouble() == 1.0) {
				return input;
			}
		}

		return (Expression<Double>) flat;
	}

	@Override
	public Number kernelValue(int kernelIndex) {
		if (fp) {
			return getChildren().get(0).kernelValue(kernelIndex).doubleValue() % getChildren().get(1).kernelValue(kernelIndex).doubleValue();
		} else {
			return getChildren().get(0).kernelValue(kernelIndex).intValue() % getChildren().get(1).kernelValue(kernelIndex).intValue();
		}
	}
}
