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

import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelSeriesMatcher;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.lang.LanguageOperations;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Mod<T extends Number> extends Expression<T> {
	public static boolean enableKernelSimplification = true;
	public static boolean enableKernelWarnings = false;

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
				"(" + getChildren().get(0).getExpression(lang) + ") % (" + getChildren().get(1).getExpression(lang) + ")";
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
	public OptionalInt upperBound() {
		return getChildren().get(1).upperBound();
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return new Mod((Expression<Double>) children.get(0), (Expression<Double>) children.get(1), fp);
	}

	@Override
	public Expression simplify(KernelSeriesProvider provider) {
		Expression<?> flat = super.simplify(provider);
		if (!enableSimplification) return (Expression<Double>) flat;
		if (!(flat instanceof Mod)) return (Expression<Double>) flat;

		Expression input = flat.getChildren().get(0);
		Expression mod = flat.getChildren().get(1);

		if (input.intValue().isPresent()) {
			if (input.intValue().getAsInt() == 0) {
				return (Expression) new IntegerConstant(0);
			} else if (mod.intValue().isPresent() && !fp) {
				if (mod.intValue().getAsInt() == 1) {
					return input;
				} else if (mod.intValue().getAsInt() != 0) {
					return (Expression) new IntegerConstant(input.intValue().getAsInt() % mod.intValue().getAsInt());
				} else {
					System.out.println("WARN: Module zero encountered while simplifying expression");
				}
			}
		} else if (mod.intValue().isPresent()) {
			int m = mod.intValue().getAsInt();

			if (m == 1) {
				return (Expression) new IntegerConstant(0);
			} else if (enableKernelSimplification && input.isKernelValue()) {
				OptionalInt limit = input.kernelSeries().loop(m).getPeriod();

				if (limit.isPresent()) {
					if (limit.getAsInt() > 2048) {
						if (enableKernelWarnings)
							System.out.println("WARN: Kernel series period is very large");
					} else {
						List<Number> distinct = input.imod(m).getDistinctKernelValues(limit.getAsInt());

						if (distinct != null && distinct.size() == 1) {
							if (distinct.get(0) instanceof Integer) {
								return new IntegerConstant(distinct.get(0).intValue());
							} else {
								return new DoubleConstant(distinct.get(0).doubleValue());
							}
						}

						return KernelSeriesMatcher.simplify(input.imod(m), m);
					}
				}
			} else if (input.isKernelValue()) {
				OptionalInt limit = input.kernelSeries().loop(m).getPeriod();

				if (limit.isPresent()) {
					if (limit.getAsInt() > 10000) {
						if (enableKernelWarnings)
							System.out.println("WARN: Kernel series period is very large");
					} else {
						Number values[] = input.kernelSeq(limit.getAsInt());

						if (Arrays.stream(values).allMatch(i -> i instanceof Integer) &&
								Arrays.stream(values).mapToInt(i -> i.intValue() % m).distinct().count() == 1) {
							return new IntegerConstant(values[0].intValue() % m);
						}
					}
				}
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

		return (Expression<Double>) flat;
	}

	@Override
	public boolean isKernelValue() {
		return getChildren().get(0).isKernelValue() && getChildren().get(1).isKernelValue();
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
