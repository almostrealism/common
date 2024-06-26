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

import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ExpressionCache;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class Mod<T extends Number> extends BinaryExpression<T> {
	public static boolean enableMod2Optimization = false;

	private boolean fp;

	protected Mod(Expression<T> a, Expression<T> b, boolean fp) {
		super(a.getType(), a, b);
		this.fp = fp;

		if (!fp && (a.isFP() || b.isFP()))
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
	public boolean isValue(IndexValues values) {
		return getChildren().get(0).isValue(values) && getChildren().get(1).isValue(values);
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
	public IndexSequence sequence(Index index, long len, long limit) {
		if (!isInt() || getChildren().get(1).intValue().isEmpty())
			return super.sequence(index, len, limit);

		IndexSequence seq = getChildren().get(0).sequence(index, len, limit);
		if (seq == null) return null;

		return seq.mod(getChildren().get(1).intValue().getAsInt());
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		if (children.size() != 2) {
			throw new UnsupportedOperationException();
		}

		return Mod.of(children.get(0), children.get(1), fp);
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
		} else if (mod.longValue().isPresent()) {
			long m = mod.longValue().getAsLong();
			if (m == 1) return new IntegerConstant(0);

			if (!input.isFP()) {
				if (input instanceof Mod) {
					Expression simple = tryModSimplify((Mod) input, m);
					if (simple != null) return simple;
				} else if (input instanceof Sum) {
					Expression simple = trySumSimplify((Sum) input, m);
					if (simple != null) return simple;
				}
			}

			OptionalLong u = input.upperBound(context);
			if (u.isPresent() && u.getAsLong() < m) {
				return input;
			} else if (enableMod2Optimization && isPowerOf2(m) && m < Integer.MAX_VALUE) {
				return new And(input, new IntegerConstant((int) m - 1));
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

	public static Expression of(Expression... inputs) {
		if (inputs.length != 2) {
			throw new UnsupportedOperationException();
		}

		return of(inputs[0], inputs[1], inputs[0].isFP() || inputs[1].isFP());
	}

	public static Expression of(Expression input, Expression mod) {
		return of(input, mod, true);
	}

	public static Expression of(Expression input, Expression mod, boolean fp) {
		return ExpressionCache.match(create(input, mod, fp));
	}

	protected static Expression create(Expression input, Expression mod, boolean fp) {
		if (fp || mod.intValue().isEmpty()) return new Mod(input, mod, fp);

		int m = mod.intValue().getAsInt();

		if (input instanceof Mod && input.isInt()) {
			Mod<Integer> innerMod = (Mod) input;
			OptionalInt inMod = innerMod.getChildren().get(1).intValue();

			if (inMod.isPresent()) {
				int n = inMod.getAsInt();

				if (n == m) {
					return innerMod;
				} else if (n % m == 0) {
					return new Mod(innerMod.getChildren().get(0), new IntegerConstant(m), false);
				}
			}
		}

		return new Mod(input, mod, fp);
	}

	private static Expression tryModSimplify(Mod<?> innerMod, long m) {
		OptionalLong inMod = innerMod.getChildren().get(1).longValue();

		if (inMod.isPresent()) {
			long n = inMod.getAsLong();

			if (n == m) {
				return innerMod;
			} else if (n % m == 0) {
				return new Mod(innerMod.getChildren().get(0), Constant.of(m), false);
			}
		}

		return null;
	}

	private static Expression trySumSimplify(Sum<?> innerSum, long m) {
		if (innerSum.getChildren().size() != 2) return null;

		Product<?> product = (Product) innerSum.getChildren().stream()
				.filter(e -> e instanceof Product)
				.findFirst().orElse(null);
		if (product == null) return null;
		if (product.getChildren().size() != 2) return null;

		Expression<?> arg = product.getChildren()
				.stream().filter(e -> e.longValue().isEmpty())
				.findFirst().orElse(null);
		if (arg == null) return null;

		long constant = product.getChildren().stream()
				.map(Expression::longValue)
				.filter(OptionalLong::isPresent).findFirst()
				.map(OptionalLong::getAsLong).orElse(-1L);
		if (constant <= 0) return null;
		if (m % constant != 0) return null;
		if (constant > m) return null;

		Mod<?> mod = (Mod) innerSum.getChildren().stream()
				.filter(e -> e instanceof Mod)
				.findFirst().orElse(null);
		if (mod == null) return null;
		if (mod.isFP()) return null;
		if (!mod.getChildren().get(0).equals(arg)) return null;
		if (mod.getChildren().get(1).longValue().isEmpty()) return null;
		if (mod.getChildren().get(1).longValue().getAsLong() != constant) return null;

		return new Mod(arg, Constant.of(m), false);
	}

	private static boolean isPowerOf2(long number) {
		return number > 0 && (number & (number - 1)) == 0;
	}
}
