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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

// TODO  Shouldn't this extend NAryDynamicProducer?
public class ScalarBankProduct extends DynamicProducerComputationAdapter<ScalarBank, ScalarBank> implements ScalarBankProducer {
	public ScalarBankProduct(int count, Supplier<Evaluable<? extends ScalarBank>> a,
							 Supplier<Evaluable<? extends ScalarBank>> b) {
		super(count * 2, () -> args -> new ScalarBank(count),
				i -> { throw new UnsupportedOperationException(); },
				a, b);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> new Product(getArgument(1).valueAt(i), getArgument(2).valueAt(i));
	}
}
