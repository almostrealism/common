/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.NAryExpression;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.MemWrapper;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class NAryDynamicProducer<T extends MemWrapper> extends DynamicProducerComputationAdapter<T, T> implements ComputerFeatures {
	private final String operator;
	private Expression<Double> value[];
	private boolean isStatic;

	@SafeVarargs
	public NAryDynamicProducer(String operator, int memLength, Supplier<Evaluable<? extends T>> blank,
							   IntFunction<MemoryBank<T>> kernelDestination,
							   Supplier<Evaluable<? extends T>>... producers) {
		super(memLength, blank, kernelDestination, producers);
		this.operator = operator;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null || value[pos] == null) {
				List<Expression<Double>> params = new ArrayList<>();

				for (int i = 1; i < getArgsCount(); i++) {
					params.add(getArgument(i).get(pos));
				}

				return new NAryExpression(Double.class, operator, params);
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = new Expression[getMemLength()];

			List<ArrayVariable<? extends T>> p = getArgumentVariables();

			List<ArrayVariable<? extends T>> staticProducers = extractStaticProducers(p);
			List<ArrayVariable<? extends T>> dynamicProducers = extractDynamicProducers(p);

			boolean valueStatic[] = new boolean[value.length];

			String removed = null;

			pos: for (int pos = 0; pos < getMemLength(); pos++) {
				StringBuffer buf = new StringBuffer();

				if (staticProducers.size() > 0) {
					double staticProduct = getIdentity();

					for (int i = 0; i < staticProducers.size(); i++) {
						staticProduct = combine(staticProduct,
								doubleForString(getExpression(staticProducers.get(i), pos).getExpression()));
					}

					Double replace = isReplaceAll(staticProduct);
					if (replace != null) {
						value[pos] = new Expression(Double.class, stringForDouble(replace));
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

				List<Variable<?, ?>> deps = new ArrayList<>();

				for (int i = 0; i < dynamicProducers.size(); i++) {
					Expression e = getExpression(dynamicProducers.get(i), pos);
					deps.addAll(e.getDependencies());

					absorbVariables(dynamicProducers.get(i).getProducer());
					buf.append("(");
					buf.append(e.getExpression());
					buf.append(")");
					if (i < dynamicProducers.size() - 1) buf.append(" ").append(operator).append(" ");
				}

				value[pos] = new Expression<>(Double.class, buf.length() > 0 ? buf.toString() : removed, deps.toArray(new Variable[0]));
				if (value[pos].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			// If there are no dynamic dependencies, or if all values have been replaced
			// by a fixed value, this producer itself is static
			if (dynamicProducers.isEmpty() || allTrue(valueStatic)) isStatic = true;
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
	 * {@link Evaluable} is static. If so, this method returns
	 * true.
	 */
	@Override
	public boolean isStatic() { return !isVariableRef() && isStatic; }

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
