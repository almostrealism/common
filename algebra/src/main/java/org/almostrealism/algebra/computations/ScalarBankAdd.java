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
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ScalarBankAdd extends DynamicProducerComputationAdapter<ScalarBank, ScalarBank> implements ScalarBankProducer {
	public ScalarBankAdd(int count, Supplier<Evaluable<? extends ScalarBank>> input,
						 Supplier<Evaluable<? extends Scalar>> value) {
		super(count * 2, () -> args -> new ScalarBank(count),
				i -> { throw new UnsupportedOperationException(); },
				input, (Supplier) value);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> i % 2 == 0 ? new Sum(getArgument(1).valueAt(i), getArgument(2).valueAt(0))
									: getArgument(1).valueAt(i);
	}
}
