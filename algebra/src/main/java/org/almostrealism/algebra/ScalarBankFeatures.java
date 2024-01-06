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
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.Input;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface ScalarBankFeatures extends ScalarFeatures {
	boolean enableDeprecated = !SystemUtils.isEnabled("AR_HARDWARE_CL_NATIVE").orElse(false);

	default ExpressionComputation<PackedCollection<Scalar>> value(PackedCollection<Scalar> value) {
		return ExpressionComputation.fixed(value, Scalar.scalarBankPostprocessor());
	}

	@Deprecated
	default ExpressionComputation<PackedCollection<Scalar>> scalarBankAdd(int count, Producer<PackedCollection<Scalar>> input,
						  						Supplier<Evaluable<? extends Scalar>> value) {
		if (!enableDeprecated) throw new UnsupportedOperationException();

		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 2 * count).forEach(i ->
				expression.add(args -> i % 2 == 0 ?
						Sum.of(args.get(1).getValueRelative(i), args.get(2).getValueRelative(0)) : args.get(1).getValueRelative(i)));
		return (ExpressionComputation) new ExpressionComputation<>(expression, (Supplier) input, (Supplier) value)
				.setPostprocessor(Scalar.scalarBankPostprocessor());
	}

	@Deprecated
	default Producer<PackedCollection<Scalar>> scalarBankProduct(int count,
																 Producer<PackedCollection<Scalar>> a,
												   				 Producer<PackedCollection<Scalar>> b) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends PackedCollection<Scalar>> x = a.get();
			Evaluable<? extends PackedCollection<Scalar>> y = b.get();
			Evaluable<Scalar> ev = ops.scalarsMultiply(Input.value(2, 0), Input.value(2, 1)).get();

			return args -> {
				PackedCollection<Scalar> d1 = x.evaluate(args);
				PackedCollection<Scalar> d2 = y.evaluate(args);
				PackedCollection<Scalar> out = Scalar.scalarBank(count);
				ev.into(out).evaluate(d1, d2);
				return out;
			};
		};
	}

	default ExpressionComputation<PackedCollection<Scalar>> dither(int count,
										  Producer<PackedCollection<Scalar>> input,
				   						  Supplier<Evaluable<? extends Scalar>> ditherValue) {
		ditherValue = scalarsMultiply(ditherValue, scalar(shape(1), randn(shape(1)), 0));
		return scalarBankAdd(count, input, ditherValue);
	}

	default ExpressionComputation<PackedCollection<Scalar>> ditherAndRemoveDcOffset(int count,
														   Producer<PackedCollection<Scalar>> input,
														   Supplier<Evaluable<? extends Scalar>> ditherValue) {
		ExpressionComputation<PackedCollection<Scalar>> dither = dither(count, input, ditherValue);
		return scalarBankAdd(count, dither, scalar(subset(shape(count, 1), dither, 0).sum().divide(c(count)).multiply(c(-1))));
	}

	default Producer<PackedCollection<Scalar>> preemphasize(int count, Supplier<Evaluable<? extends PackedCollection<Scalar>>> input,
											  Supplier<Evaluable<? extends Scalar>> coefficient) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends Scalar> coeff = coefficient.get();
			Evaluable<? extends PackedCollection<Scalar>> in = input.get();
			ExpressionComputation<Scalar> offset = ops.scalarsMultiply(Input.value(2, 1), Input.value(2, 2));
			Evaluable<Scalar> ev = ops.scalarSubtract(Input.value(2, 0), offset).get();

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

	default ExpressionComputation<PackedCollection<Scalar>> scalars(Supplier<Evaluable<? extends Scalar>>... values) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 2 * values.length).forEach(i -> expression.add(args -> args.get(i / 2 + 1).getValueRelative(i % 2)));
		return (ExpressionComputation) new ExpressionComputation<>(expression, (Supplier[]) values)
				.setPostprocessor(Scalar.scalarBankPostprocessor());
	}

	static ScalarBankFeatures getInstance() {
		return new ScalarBankFeatures() {};
	}
}
