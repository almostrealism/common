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
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducer;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PowerSpectrum extends DynamicProducerComputationAdapter<PairBank, ScalarBank> implements ScalarBankProducer {
	public int inputCount;

	public PowerSpectrum(int count, Supplier<Evaluable<? extends PairBank>> input) {
		super(2 * (count / 2 + 1), () -> args -> new ScalarBank(count),
				i -> { throw new UnsupportedOperationException(); }, input);
		this.inputCount = count;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			if (i % 2 == 0) {
				if (i == 0) {
					return new Product(getArgument(1, inputCount).valueAt(0), getArgument(1, inputCount).valueAt(0));
				} else if (i == getMemLength() - 2) {
					return new Product(getArgument(1, inputCount).valueAt(1), getArgument(1, inputCount).valueAt(1));
				} else {
					return new Sum(
							new Product(getArgument(1, inputCount).valueAt(i), getArgument(1, inputCount).valueAt(i)),
							new Product(getArgument(1, inputCount).valueAt(i + 1), getArgument(1, inputCount).valueAt(i + 1)));
				}
			} else {
				return new Expression<>(Double.class, "1.0");
			}
		};
	}

	public static Producer<ScalarBank> fast(int count, Supplier<Evaluable<? extends PairBank>> input) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends PairBank> in = input.get();
			KernelizedEvaluable<Scalar> ev = ops.scalarAdd(
					ops.scalarsMultiply(Input.value(2, 0), Input.value(2, 0)),
					ops.scalarsMultiply(Input.value(2, 1), Input.value(2, 1))).get();

			return args -> {
				int tot = count / 2 + 1;
				PairBank data = in.evaluate(args);
				ScalarBank out = new ScalarBank(tot);

				ev.kernelEvaluate(out.range(1, tot - 2), data.range(1, tot - 2), data.range(1, tot - 2, true));
				out.set(0, data.get(0).r() * data.get(0).r(), 1.0);
				out.set(tot - 1, data.get(0).i() * data.get(0).i(), 1.0);
				return out;
			};
		};
	}
}
