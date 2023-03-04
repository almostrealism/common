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
import io.almostrealism.relation.Producer;
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

public class Preemphasize extends DynamicProducerComputationAdapter<ScalarBank, ScalarBank> implements ScalarBankProducer {
	public Preemphasize(int count, Supplier<Evaluable<? extends ScalarBank>> input,
				  Supplier<Evaluable<? extends Scalar>> coeff) {
		super(count * 2, () -> args -> new ScalarBank(count),
				i -> { throw new UnsupportedOperationException(); },
				input, (Supplier) coeff);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			if (i == 0) {
				return new Sum(getArgument(1).valueAt(i),
						new Minus(new Product(getArgument(2).valueAt(0), getArgument(1).valueAt(i))));
			} else if (i % 2 == 0) {
				return new Sum(getArgument(1).valueAt(i),
						new Minus(new Product(getArgument(2).valueAt(0), getArgument(1).valueAt(i - 2))));
			} else {
				return getArgument(1).valueAt(i);
			}
		};
	}

	public static Producer<ScalarBank> fast(int count, Supplier<Evaluable<? extends ScalarBank>> input,
											Supplier<Evaluable<? extends Scalar>> coefficient) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends Scalar> coeff = coefficient.get();
			Evaluable<? extends ScalarBank> in = input.get();
			ScalarProducerBase offset = ops.scalarsMultiply(Input.value(2, 1), Input.value(2, 2, -1));
			KernelizedEvaluable<Scalar> ev = ops.scalarSubtract(Input.value(2, 0), offset).get();

			return args -> {
				Scalar c = coeff.evaluate(args);
				ScalarBank data = in.evaluate(args);
				ScalarBank out = new ScalarBank(count);

				ev.kernelEvaluate(out.range(0, 1), data.range(0, 1), data.range(0, 1), c);
				ev.kernelEvaluate(out.range(1, count - 1), data.range(1, count - 1), data.range(0, count - 1), c);
				return out;
			};
		};
	}
}
