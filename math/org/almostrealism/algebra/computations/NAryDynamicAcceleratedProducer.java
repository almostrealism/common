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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public abstract class NAryDynamicAcceleratedProducer<T extends MemWrapper> extends DynamicAcceleratedProducerAdapter<T> {
	private String operator;
	private String value[];
	private boolean isStatic;

	public NAryDynamicAcceleratedProducer(String operator, int memLength, Producer<T> blank, Producer<T>... producers) {
		super(memLength, includeResult(blank, producers));
		this.operator = operator;
	}

	@Override
	public Scope<T> getScope(NameProvider p) {
		Scope<T> scope = new Scope<>();

		for (int i = 0; i < getMemLength(); i++) {
			scope.getVariables().add(new Variable(p.getArgumentValueName(0, i), getValue(i)));
		}

		return scope;
	}

	@Override
	public Function<Integer, String> getValueFunction() {
		return pos -> {
			if (value == null || value[pos] == null) {
				StringBuffer buf = new StringBuffer();

				for (int i = 1; i < getArgsCount(); i++) {
					buf.append(getArgumentValueName(i, pos));
					if (i < (getArgsCount() - 1)) buf.append(" " + operator + " ");
				}

				return buf.toString();
			} else {
				return value[pos];
			}
		};
	}

	// TODO  Combine ScalarProducts that are equal by converting to ScalarPow
	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(getInputProducers()[0]);

			value = new String[getMemLength()];

			Argument p[] = getInputProducers();

			List<Argument> staticProducers = extractStaticProducers(p);
			List<Argument> dynamicProducers = extractDynamicProducers(p);

			boolean valueStatic[] = new boolean[value.length];

			String removed = null;

			pos: for (int pos = 0; pos < getMemLength(); pos++) {
				StringBuffer buf = new StringBuffer();

				if (staticProducers.size() > 0) {
					double staticProduct = getIdentity();

					for (int i = 0; i < staticProducers.size(); i++) {
						staticProduct = combine(staticProduct,
								doubleForString(getInputProducerValue(staticProducers.get(i), pos)));
					}

					Double replace = isReplaceAll(staticProduct);
					if (replace != null) {
						value[pos] = stringForDouble(replace);
						valueStatic[pos] = true;
						continue pos;
					}

					if (isRemove(staticProduct)) {
						removed = stringForDouble(staticProduct);
					} else {
						buf.append(stringForDouble(staticProduct));
						if (dynamicProducers.size() > 0) buf.append(" " + operator + " ");
					}
				}

				for (int i = 0; i < dynamicProducers.size(); i++) {
					for (int j = 1; j < ((AcceleratedProducer) dynamicProducers.get(i).getProducer()).getInputProducers().length; j++) {
						newArgs.add(((AcceleratedProducer) dynamicProducers.get(i).getProducer()).getInputProducers()[j]);
					}
					buf.append("(");
					buf.append(getInputProducerValue(dynamicProducers.get(i), pos));
					buf.append(")");
					if (i < (dynamicProducers.size() - 1)) buf.append(" " + operator + " ");
				}

				value[pos] = buf.length() > 0 ? buf.toString() : removed;
				if (value[pos].contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			// If there are no dynamic dependencies, or if all values have been replaced
			// by a fixed value, this producer itself is static
			if (dynamicProducers.isEmpty() || allTrue(valueStatic)) isStatic = true;

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}

	private boolean allTrue(boolean valueStatic[]) {
		for (boolean b : valueStatic) {
			if (!b) return false;
		}

		return true;
	}

	/**
	 * After {@link #compact()}, it may be determined that this
	 * {@link Producer} is static. If so, this method returns
	 * true.
	 */
	public boolean isStatic() { return isStatic; }

	/**
	 * Returns the identity value for this n-ary operator.
	 */
	public abstract double getIdentity();

	/**
	 * Combines the two values as if they are arguments to this n-ary operator.
	 */
	public abstract double combine(double a, double b);

	/**
	 * Override this method if there is a known value that can result in the
	 * entire expression being replaced by a fixed value. This is for scenarios
	 * like multiplication, where the presence of zero means that the entire
	 * expression can be replaced with zero.
	 */
	public Double isReplaceAll(double value) { return null; }

	/**
	 * Override this method if there is a known value that does not need to
	 * be included. This is for scenarios like addition, where the presence
	 * of zero means there is no need to include the value.
	 */
	public boolean isRemove(double value) { return false; }
}
