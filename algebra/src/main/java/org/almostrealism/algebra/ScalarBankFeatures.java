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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface ScalarBankFeatures extends ScalarFeatures {
	default ExpressionComputation<PackedCollection<Scalar>> value(PackedCollection<Scalar> value) {
		return ExpressionComputation.fixed(value, Scalar.scalarBankPostprocessor());
	}

	@Deprecated
	default void setZero(PackedCollection<Scalar> bank) {
		int size = bank.getCount();
		for (int i = 0; i < size; i++) bank.set(i, 0.0, 1.0);
	}

	@Deprecated
	default void addMatVec(PackedCollection<Scalar> bank, ScalarTable matrix, PackedCollection<Scalar> vector) {
		int m = matrix.getCount();
		int n = matrix.getWidth();
		assert n == vector.getCount();

		for (int i = 0; i < m; i++) {
			double v = 0;

			for (int j = 0; j < n; j++) {
				v += matrix.get(i, j).getValue() * vector.get(j).getValue();
			}

			bank.set(i, v, 1.0);
		}
	}

	@Deprecated
	default void mulElements(PackedCollection<Scalar> bank, PackedCollection<Scalar> vals) {
		int size = bank.getCount();
		assert size == vals.getCount();

		IntStream.range(0, size)
				.forEach(i ->
						bank.set(i,
								bank.get(i).getValue() * vals.get(i).getValue(),
								bank.get(i).getCertainty() * vals.get(i).getCertainty()));
	}

	@Deprecated
	default void applyFloor(PackedCollection<Scalar> bank, double floor) {
		for (int i = 0; i < bank.getCount(); i++) {
			double v = bank.get(i).getValue();
			if (v < floor) bank.set(i, floor);
		}
	}

	@Deprecated
	default void applyLog(PackedCollection<Scalar> bank) {
		for (int i = 0; i < bank.getCount(); i++) {
			bank.set(i, Math.log(bank.get(i).getValue()));
		}
	}

	@Deprecated
	default ScalarBankProducerBase scalarBankAdd(int count, Producer<PackedCollection<Scalar>> input,
						  						Supplier<Evaluable<? extends Scalar>> value) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 2 * count).forEach(i ->
				expression.add(args -> i % 2 == 0 ?
						new Sum(args.get(1).getValue(i), args.get(2).getValue(0)) : args.get(1).getValue(i)));
		return new ScalarBankExpressionComputation(expression, (Supplier) input, (Supplier) value);
	}

	@Deprecated
	default Producer<PackedCollection<Scalar>> scalarBankProduct(int count,
																 Producer<PackedCollection<Scalar>> a,
												   				 Producer<PackedCollection<Scalar>> b) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends PackedCollection<Scalar>> x = a.get();
			Evaluable<? extends PackedCollection<Scalar>> y = b.get();
			KernelizedEvaluable<Scalar> ev = ops.scalarsMultiply(Input.value(2, 0), Input.value(2, 1)).get();

			return args -> {
				PackedCollection<Scalar> d1 = x.evaluate(args);
				PackedCollection<Scalar> d2 = y.evaluate(args);
				PackedCollection<Scalar> out = Scalar.scalarBank(count);
				ev.into(out).evaluate(d1, d2);
				return out;
			};
		};
	}

	default ScalarBankProducerBase dither(int count,
										  Producer<PackedCollection<Scalar>> input,
				   						  Supplier<Evaluable<? extends Scalar>> ditherValue) {
		ditherValue = scalarsMultiply(ditherValue, scalar(shape(1), randn(shape(1)), 0));
		return scalarBankAdd(count, input, ditherValue);
	}

	default ScalarBankProducerBase ditherAndRemoveDcOffset(int count,
														   Producer<PackedCollection<Scalar>> input,
														   Supplier<Evaluable<? extends Scalar>> ditherValue) {
		ScalarBankProducerBase dither = dither(count, input, ditherValue);
		return scalarBankAdd(count, dither, scalar(subset(shape(count, 1), dither, 0).sum().divide(c(count)).multiply(c(-1))));
	}

	default Producer<PackedCollection<Scalar>> preemphasize(int count, Supplier<Evaluable<? extends PackedCollection<Scalar>>> input,
											  Supplier<Evaluable<? extends Scalar>> coefficient) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends Scalar> coeff = coefficient.get();
			Evaluable<? extends PackedCollection<Scalar>> in = input.get();
			ExpressionComputation<Scalar> offset = ops.scalarsMultiply(Input.value(2, 1), Input.value(2, 2, -1));
			KernelizedEvaluable<Scalar> ev = ops.scalarSubtract(Input.value(2, 0), offset).get();

			return args -> {
				Scalar c = coeff.evaluate(args);
				PackedCollection<Scalar> data = in.evaluate(args);
				PackedCollection<Scalar> out = Scalar.scalarBank(count);

				ev.into(out.range(shape(1, 2)).traverse(1))
						.evaluate(data.range(shape(1, 2)).traverse(1),
								  data.range(shape(1, 2)).traverse(1), c);
				ev.into(out.range(shape(count - 1, 2), 2).traverse(1))
						.evaluate(data.range(shape(count - 1, 2), 2).traverse(1),
								  data.range(shape(count - 1, 2)).traverse(1), c);
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
