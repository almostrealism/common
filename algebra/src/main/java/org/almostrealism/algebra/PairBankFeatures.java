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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface PairBankFeatures extends ScalarFeatures {

	default ExpressionComputation<PackedCollection<Pair<?>>> pairBank(Supplier<Evaluable<? extends Pair<?>>>... input) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 2 * input.length).forEach(i -> comp.add(args -> args.get(1 + i / 2).getValueRelative(i % 2)));
		return (ExpressionComputation) new ExpressionComputation(shape(input.length, 2).traverse(0), comp, input)
				.setPostprocessor(Pair.bankPostprocessor());
	}

	default Producer<Pair<?>> pairFromBank(Producer<PackedCollection<Pair<?>>> bank, Producer<PackedCollection<?>> index) {
		Producer<PackedCollection<?>> pair = map(shape(2), traverse(1, floor(index)),
				v -> concat((Producer) c(2.0).multiply(v), (Producer) c(2.0).multiply(v).add(c(1.0))));


		return (Producer) c(shape(2), bank, pair);
	}

	default Producer<PackedCollection<Scalar>> powerSpectrum(int count, Supplier<Evaluable<? extends PackedCollection<Pair<?>>>> input) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends PackedCollection<Pair<?>>> in = input.get();
			KernelizedEvaluable<Scalar> ev = ops.scalarAdd(
					ops.scalarsMultiply(Input.value(2, 0), Input.value(2, 0)),
					ops.scalarsMultiply(Input.value(2, 1), Input.value(2, 1))).get();

			return args -> {
				int tot = count / 2 + 1;
				PackedCollection<Pair<?>> data = in.evaluate(args);
				PackedCollection<Scalar> out = Scalar.scalarBank(tot);

				ev.into(out.range(shape(tot - 2, 2), 2).traverse(1)).evaluate(
						data.range(shape(tot - 2, 2), 2).traverse(1),
						data.range(shape(tot - 2, 2), 3).traverse(1));
				out.set(0, data.get(0).r() * data.get(0).r(), 1.0);
				out.set(tot - 1, data.get(0).i() * data.get(0).i(), 1.0);
				return out;
			};
		};
	}
}
