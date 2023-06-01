/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.ScalarBankExpressionComputation;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface ScalarBankFeatures extends ScalarFeatures {
	default ExpressionComputation<ScalarBank> value(ScalarBank value) {
		return ExpressionComputation.fixed(value, ScalarBank.postprocessor());
	}

	@Deprecated
	default ScalarBankProducerBase scalarBankAdd(int count, Supplier<Evaluable<? extends ScalarBank>> input,
						  						Supplier<Evaluable<? extends Scalar>> value) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 2 * count).forEach(i ->
				expression.add(args -> i % 2 == 0 ?
						new Sum(args.get(1).getValue(i), args.get(2).getValue(0)) : args.get(1).getValue(i)));
		return new ScalarBankExpressionComputation(expression, (Supplier) input, (Supplier) value);
	}

	@Deprecated
	default Producer<ScalarBank> scalarBankProduct(int count, Supplier<Evaluable<? extends ScalarBank>> a,
												   Supplier<Evaluable<? extends ScalarBank>> b) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends ScalarBank> x = a.get();
			Evaluable<? extends ScalarBank> y = b.get();
			KernelizedEvaluable<Scalar> ev = ops.scalarsMultiply(Input.value(2, 0), Input.value(2, 1)).get();

			return args -> {
				ScalarBank d1 = x.evaluate(args);
				ScalarBank d2 = y.evaluate(args);
				ScalarBank out = new ScalarBank(count);
				ev.into(out).evaluate(d1, d2);
				return out;
			};
		};
	}

	default ScalarBankProducerBase dither(int count, Supplier<Evaluable<? extends ScalarBank>> input,
				   Supplier<Evaluable<? extends Scalar>> ditherValue) {
		ditherValue = scalarsMultiply(ditherValue, scalar(shape(1), randn(shape(1)), 0));
		return scalarBankAdd(count, input, ditherValue);
	}

	default ScalarBankProducerBase ditherAndRemoveDcOffset(int count, Supplier<Evaluable<? extends ScalarBank>> input,
														   Supplier<Evaluable<? extends Scalar>> ditherValue) {
		ScalarBankProducerBase dither = dither(count, input, ditherValue);
		return scalarBankAdd(count, dither, scalar(subset(shape(count, 1), dither, 0).sum().divide(c(count)).multiply(c(-1))));
	}

	default Producer<ScalarBank> preemphasize(int count, Supplier<Evaluable<? extends ScalarBank>> input,
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

				ev.into(out.range(0, 1))
						.evaluate(data.range(0, 1), data.range(0, 1), c);
				ev.into(out.range(1, count - 1))
						.evaluate(data.range(1, count - 1), data.range(0, count - 1), c);
				return out;
			};
		};
	}

	default ScalarBankProducerBase scalars(Supplier<Evaluable<? extends Scalar>>... values) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 2 * values.length).forEach(i -> expression.add(args -> args.get(i / 2 + 1).getValue(i % 2)));
		return new ScalarBankExpressionComputation(expression, (Supplier[]) values);
	}

	static ScalarBankFeatures getInstance() {
		return new ScalarBankFeatures() {};
	}
}
