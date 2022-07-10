/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.collect;

import io.almostrealism.code.NameProvider;
import io.almostrealism.expression.Expression;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class ExpressionComputation<T extends MemoryData> extends DynamicProducerComputationAdapter<T, T> implements ComputerFeatures {
	private Function<NameProvider, Expression<Double>> expression;
	private Expression<Double> value;

	@SafeVarargs
	public ExpressionComputation(Supplier<Evaluable<? extends T>> blank,
							   IntFunction<MemoryBank<T>> kernelDestination,
							   Function<NameProvider, Expression<Double>> expression,
							   Supplier<Evaluable<? extends T>>... args) {
		super(1, blank, kernelDestination, args);
		this.expression = expression;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				return expression.apply(this);
			} else {
				return value;
			}
		};
	}

	@Override
	public synchronized void compact() {
		super.compact();
		// TODO  Instantiate instance variable 'value' so that expression need not be applied
	}
}
