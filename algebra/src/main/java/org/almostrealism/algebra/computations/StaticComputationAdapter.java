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

import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Pair;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class StaticComputationAdapter<T extends MemoryData> extends DynamicProducerComputationAdapter<MemoryData, T> {
	private T value;

	public StaticComputationAdapter(T value, Supplier<Evaluable<? extends T>> output,
									IntFunction<MemoryBank<T>> kernelDestination) {
		super(value.getMemLength(), output, kernelDestination);
		this.value = value;
	}

	public T getValue() { return value; }

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			String s = stringForDouble(value.getMem().toArray(value.getOffset() + pos, 1)[0]);
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
