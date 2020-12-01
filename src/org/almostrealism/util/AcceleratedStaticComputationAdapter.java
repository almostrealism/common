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

package org.almostrealism.util;

import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Pair;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class AcceleratedStaticComputationAdapter<T extends MemWrapper> extends DynamicAcceleratedProducerAdapter<MemWrapper, T> {
	private T value;

	public AcceleratedStaticComputationAdapter(T value, Supplier<Evaluable<? extends T>> output) {
		super(value.getMemLength(), output);
		this.value = value;
	}

	public T getValue() { return value; }

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			Pair p = MemWrapper.fromMem(value.getMem(), value.getOffset() + pos, 1);

			String s = stringForDouble(p.getA());
			if (s.contains("Infinity")) {
				throw new IllegalArgumentException("Infinity is not supported");
			}

			return new Expression<>(Double.class, s);
		};
	}

	/**
	 * Returns true.
	 */
	@Override
	public boolean isStatic() { return !isVariableRef() && true; }
}
