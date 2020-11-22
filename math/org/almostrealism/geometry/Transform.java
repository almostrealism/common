/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.geometry;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Expression;
import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorSupplier;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Evaluable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class Transform extends DynamicAcceleratedProducerAdapter<Vector, Vector> implements VectorSupplier {
	private boolean includeTranslation;

	private Expression<Double> value[];

	public Transform(TransformMatrix t, Supplier<Evaluable<? extends Vector>> v, boolean includeTranslation) {
		super(3, () -> Vector.blank(), new Supplier[] { v }, new Object[] { t });
		this.includeTranslation = includeTranslation;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				StringBuffer buf = new StringBuffer();
				buf.append(t(pos, 0));
				buf.append(" * ");
				buf.append(v(0));
				buf.append(" + ");
				buf.append(t(pos, 1));
				buf.append(" * ");
				buf.append(v(1));
				buf.append(" + ");
				buf.append(t(pos, 2));
				buf.append(" * ");
				buf.append(v(2));

				if (includeTranslation) {
					buf.append(" + ");
					buf.append(t(pos, 3));
				}

				return new Expression(Double.class, buf.toString(), getArgument(1), getArgument(2));
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = new Expression[3];

			for (int i = 0; i < value.length; i++) {
				List<Product> sum = new ArrayList<>();
				sum.add(new Product(new Number(getInputProducerValue(2, 4 * i), getInputProducer(2).isStatic()),
									new Number(getInputProducerValue(1, 0), getInputProducer(1).isStatic())));
				sum.add(new Product(new Number(getInputProducerValue(2, 4 * i + 1), getInputProducer(2).isStatic()),
									new Number(getInputProducerValue(1, 1), getInputProducer(1).isStatic())));
				sum.add(new Product(new Number(getInputProducerValue(2, 4 * i + 2), getInputProducer(2).isStatic()),
									new Number(getInputProducerValue(1, 2), getInputProducer(1).isStatic())));

				if (includeTranslation) {
					sum.add(new Product(new Number(getInputProducerValue(2, 4 * i + 3),
													getInputProducer(2).isStatic()),
										new Number(1.0)));
				}

				Number n = new Number();

				// Combine all static entries
				Iterator<Product> itr = sum.iterator();
				while (itr.hasNext()) {
					Product p = itr.next();

					if (p.isStatic()) {
						n.addTo(new Number(p.toExpression(), true));
						itr.remove();
					}
				}

				// Remove all instances of "0.0"
				itr = sum.iterator();
				while (itr.hasNext()) {
					if (itr.next().isZero()) itr.remove();
				}

				if (n.isZero() && sum.size() <= 0) {
					value[i] = new Expression<>(Double.class, stringForDouble(0.0));
				} else {
					StringBuffer buf = new StringBuffer();

					if (!n.isZero()) {
						buf.append(n);
						if (sum.size() > 0) buf.append(" + ");
					}

					for (int j = 0; j < sum.size(); j++) {
						buf.append(sum.get(j));
						if (j < sum.size() - 1) buf.append(" + ");
					}

					value[i] = new Expression<>(Double.class, buf.toString());
				}
			}

			for (int i = 0; i < value.length; i++) {
				if (value[i].getExpression().trim().length() <= 0) {
					throw new IllegalArgumentException("Empty value for index " + i);
				}
			}

			// TODO  If both are static, this should be marked as static
			if (!getInputProducer(1).isStatic()) {
				List<Argument<? extends Vector>> args = AcceleratedProducer.excludeResult(getInputProducer(1).getArguments());
				for (Expression e : value) e.getDependencies().addAll(args);
			}

			if (!getInputProducer(2).isStatic()) {
				List<Argument<? extends Vector>> args = AcceleratedProducer.excludeResult(getInputProducer(2).getArguments());
				for (Expression e : value) e.getDependencies().addAll(args);
			}

			absorbVariables(getInputProducer(1));
			absorbVariables(getInputProducer(2));
		}
	}

	private String t(int pos, int index) {
		return getArgumentValueName(2, pos * 4 + index);
	}

	private String v(int index) {
		return getArgumentValueName(1, index);
	}

	private class Product {
		Number a, b;

		public Product(Number a, Number b) {
			this.a = a;
			this.b = b;
		}
		
		public boolean isStatic() {
			return a.isStatic && b.isStatic;
		}

		public boolean isZero() {
			return a.isZero() || b.isZero();
		}

		public Expression toExpression() { return new Expression(Double.class, toString()); }

		public String toString() {
			if (isStatic()) {
				return stringForDouble(doubleForString(a.toString()) * doubleForString(b.toString()));
			} else if (a.isMultiplicativeIdentity()) {
				return b.toString();
			} else if (b.isMultiplicativeIdentity()) {
				return a.toString();
			} else {
				return "(" + a.toString() + " * " + b.toString() + ")";
			}
		}
	}

	private class Number {
		Expression number;
		boolean isStatic;

		public Number() {
			number = new Expression(Double.class, stringForDouble(0.0));
			isStatic = true;
		}

		public Number(double value) {
			this(new Expression(Double.class, stringForDouble(value)), true);
		}

		public Number(Expression number, boolean isStatic) {
			this.number = number;
			this.isStatic = isStatic;
		}

		public void addTo(Number n) {
			if (!isStatic || !n.isStatic) throw new IllegalArgumentException("Cannot add to unless isStatic");
			number = new Expression(Double.class, stringForDouble(doubleForString(number.getExpression()) +
														doubleForString(n.number.getExpression())));
		}

		public boolean isMultiplicativeIdentity() {
			return isStatic && doubleForString(number.getExpression()) == 1.0;
		}

		public boolean isZero() {
			return isStatic && doubleForString(number.getExpression()) == 0.0;
		}

		@Override
		public String toString() { return number.getExpression(); }
	}
}
