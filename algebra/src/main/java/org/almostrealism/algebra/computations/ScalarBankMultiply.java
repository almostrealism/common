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

import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.Product;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ScalarBankMultiply extends DynamicProducerComputationAdapter<ScalarBank, ScalarBank> implements ScalarBankProducer {
	public ScalarBankMultiply(int count, Supplier<Evaluable<? extends ScalarBank>> input,
						 Supplier<Evaluable<? extends ScalarBank>> value) {
		super(count * 2, () -> args -> new ScalarBank(count),
				i -> { throw new UnsupportedOperationException(); },
				input, value);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> new Product(getArgument(1).get(i), getArgument(2).get(i));
	}
}
