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
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.PairProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ComplexProduct extends DynamicProducerComputationAdapter<Pair, Pair> implements PairProducer {

	public ComplexProduct(Supplier<Evaluable<? extends Pair>> p, Supplier<Evaluable<? extends Pair>> q) {
		super(2, Pair.empty(), PairBank::new, p, q);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			Expression p = getInputValue(1, 0);
			Expression q = getInputValue(1, 1);
			Expression r = getInputValue(2, 0);
			Expression s = getInputValue(2, 1);

			if (i == 0) {
				return new Sum(new Product(p, r), new Minus(new Product(q, s)));
			} else if (i == 1) {
				return new Sum(new Product(p, s), new Product(q, r));
			} else {
				throw new IllegalArgumentException();
			}
		};
	}
}
