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
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class Mod<T extends Number> extends BinaryExpression<T> {
	public static boolean enableMod2Optimization = true;

	private boolean fp;

	public Mod(Expression<T> a, Expression<T> b) {
		this(a, b, true);
	}

	public Mod(Expression<T> a, Expression<T> b, boolean fp) {
		super((Class<T>) (fp ? Double.class : Integer.class),
				a, b);
		this.fp = fp;

		if (!fp && (a.getType() != Integer.class || b.getType() != Integer.class))
			throw new UnsupportedOperationException();

		if (b.intValue().isPresent() && b.intValue().getAsInt() == 0) {
			System.out.println("WARN: Module zero encountered while creating expression");
		}
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return fp ? "fmod(" + getChildren().get(0).getExpression(lang) + ", " + getChildren().get(1).getExpression(lang) + ")" :
				getChildren().get(0).getWrappedExpression(lang) + " % " + getChildren().get(1).getWrappedExpression(lang);
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
	public KernelSeries kernelSeries() {
		KernelSeries input = getChildren().get(0).kernelSeries();
		OptionalDouble mod = getChildren().get(1).doubleValue();

		if (mod.isPresent() && mod.getAsDouble() == Math.floor(mod.getAsDouble())) {
			return input.loop((int) mod.getAsDouble());
		}

		return KernelSeries.infinite();
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		return getChildren().get(1).upperBound(context);
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new Mod(children.get(0), children.get(1), fp);
	}

	@Override
	public Expression simplify(KernelStructureContext context) {
		Expression<?> flat = super.simplify(context);
		if (!(flat instanceof Mod)) return flat;

		Expression input = flat.getChildren().get(0);
		Expression mod = flat.getChildren().get(1);

		if (input.intValue().isPresent()) {
			if (input.intValue().getAsInt() == 0) {
				return new IntegerConstant(0);
			} else if (input.intValue().getAsInt() == 1 && !fp) {
				return mod.intValue().orElse(-1) == 1 ? new IntegerConstant(0) : new IntegerConstant(1);
			} else if (mod.intValue().isPresent() && !fp) {
				if (mod.intValue().getAsInt() == 1) {
					return new IntegerConstant(0);
				} else if (mod.intValue().getAsInt() != 0) {
					return new IntegerConstant(input.intValue().getAsInt() % mod.intValue().getAsInt());
				} else {
					System.out.println("WARN: Module zero encountered while simplifying expression");
				}
			}
		} else if (mod.intValue().isPresent()) {
			int m = mod.intValue().getAsInt();
			if (m == 1) return new IntegerConstant(0);

			OptionalLong u = input.upperBound(context);
			if (u.isPresent() && u.getAsLong() < m) {
				return input;
			} else if (enableMod2Optimization && isPowerOf2(m)) {
				return new And(input, new IntegerConstant(m - 1));
			}
		} else if (input.doubleValue().isPresent()) {
			if (input.doubleValue().getAsDouble() == 0.0) {
				return new DoubleConstant(0.0);
			} else if (mod.doubleValue().isPresent() && !fp) {
				return new DoubleConstant(input.doubleValue().getAsDouble() % mod.doubleValue().getAsDouble());
			}
		} else if (mod.doubleValue().isPresent()) {
			if (mod.doubleValue().getAsDouble() == 1.0) {
				return input;
			}
		}

		return flat;
	}

	@Override
	public boolean isKernelValue(IndexValues values) {
		return getChildren().get(0).isKernelValue(values) && getChildren().get(1).isKernelValue(values);
	}

	@Override
	public Number value(IndexValues indexValues) {
		if (fp) {
			return getChildren().get(0).value(indexValues).doubleValue() % getChildren().get(1).value(indexValues).doubleValue();
		} else {
			return getChildren().get(0).value(indexValues).intValue() % getChildren().get(1).value(indexValues).intValue();
		}
	}

	@Override
	public Number evaluate(Number... children) {
		if (fp) {
			return children[0].doubleValue() % children[1].doubleValue();
		} else {
			return children[0].intValue() % children[1].intValue();
		}
	}

	private static boolean isPowerOf2(int number) {
		return number > 0 && (number & (number - 1)) == 0;
	}
}
