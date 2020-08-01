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

import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class Transform extends DynamicAcceleratedProducerAdapter<Vector> {
	private boolean includeTranslation;

	private String value[];

	public Transform(TransformMatrix t, Producer<Vector> v, boolean includeTranslation) {
		super(3, new Producer[]{ Vector.blank(), v }, new Object[]{ t });
		this.includeTranslation = includeTranslation;
	}

	@Override
	public String getValue(int pos) {
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

			return buf.toString();
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyDynamicAcceleratedAdapters()) {
			value = new String[3];

			DynamicAcceleratedProducerAdapter<Vector> v =
					(DynamicAcceleratedProducerAdapter<Vector>) inputProducers[1].getProducer();
			DynamicAcceleratedProducerAdapter<Vector> t =
					(DynamicAcceleratedProducerAdapter<Vector>) inputProducers[2].getProducer();

			for (int i = 0; i < value.length; i++) {
				List<Product> sum = new ArrayList<>();
				sum.add(new Product(new Number(t.getValue(4 * i), t.isStatic()),
									new Number(v.getValue(0), v.isStatic())));
				sum.add(new Product(new Number(t.getValue(4 * i + 1), t.isStatic()),
									new Number(v.getValue(1), v.isStatic())));
				sum.add(new Product(new Number(t.getValue(4 * i + 2), t.isStatic()),
									new Number(v.getValue(2), v.isStatic())));

				if (includeTranslation) {
					sum.add(new Product(new Number(t.getValue(4 * i + 3), t.isStatic()),
										new Number("1.0", true)));
				}

				Number n = new Number();

				// Combine all static entries
				Iterator<Product> itr = sum.iterator();
				while (itr.hasNext()) {
					Product p = itr.next();

					if (p.isStatic()) {
						n.addTo(new Number(p.toString(), true));
						itr.remove();
					}
				}

				// Remove all instances of "0.0"
				itr = sum.iterator();
				while (itr.hasNext()) {
					if (itr.next().isZero()) itr.remove();
				}

				StringBuffer buf = new StringBuffer();

				for (int j = 0; j < sum.size(); j++) {
					buf.append(sum.get(j));
					if (j < sum.size() - 1) buf.append(" + ");
				}

				value[i] = buf.toString();
			}

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);
			if (!v.isStatic()) newArgs.addAll(Arrays.asList(excludeResult(v.getInputProducers())));
			if (!t.isStatic()) newArgs.addAll(Arrays.asList(excludeResult(t.getInputProducers())));
			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}

	private String t(int pos, int index) {
		return getArgumentValueName(2, pos * 4 + index);
	}

	private String v(int index) {
		return getArgumentValueName(1, index);
	}

	private static class Product {
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

		public String toString() {
			if (isStatic()) {
				return String.valueOf(Double.parseDouble(a.toString()) * Double.parseDouble(b.toString()));
			} else if (a.isMultiplicativeIdentity()) {
				return b.toString();
			} else if (b.isMultiplicativeIdentity()) {
				return a.toString();
			} else {
				return "(" + a.toString() + " * " + b.toString() + ")";
			}
		}
	}

	private static class Number {
		String number;
		boolean isStatic;

		public Number() {
			number = "0.0";
			isStatic = true;
		}

		public Number(String number, boolean isStatic) {
			this.number = number;
			this.isStatic = isStatic;
		}

		public void addTo(Number n) {
			if (!isStatic || !n.isStatic) throw new IllegalArgumentException("Cannot add to unless isStatic");
			number = String.valueOf(Double.parseDouble(number) + Double.parseDouble(n.number));
		}

		public boolean isMultiplicativeIdentity() {
			return isStatic && Double.parseDouble(number) == 1.0;
		}

		public boolean isZero() {
			return isStatic && Double.parseDouble(number) == 0.0;
		}

		public String toString() {
			return number;
		}
	}
}
