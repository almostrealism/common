/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Input;

import java.util.function.Supplier;

public interface PairBankFeatures extends ScalarFeatures {

	default Producer<Pair<?>> pairFromBank(Producer<PackedCollection<Pair<?>>> bank, Producer<PackedCollection<?>> index) {
		int count = shape(index).getCount();
		Producer<PackedCollection<?>> pair =
				add(repeat(2, traverse(1, index)).multiply(2), repeat(count, c(0.0, 1.0)));
		return (Producer) c(shape(index).append(shape(2)), bank, pair);
	}

	default Producer<PackedCollection<Scalar>> powerSpectrum(int count, Supplier<Evaluable<? extends PackedCollection<Pair<?>>>> input) {
		return () -> {
			ScalarFeatures ops = ScalarFeatures.getInstance();

			Evaluable<? extends PackedCollection<Pair<?>>> in = input.get();
			Evaluable<Scalar> ev = ops.scalarAdd(
					ops.scalarsMultiply(Input.value(2, 0), Input.value(2, 0)),
					ops.scalarsMultiply(Input.value(2, 1), Input.value(2, 1))).get();

			return args -> {
				int tot = count / 2 + 1;
				PackedCollection<Pair<?>> data = in.evaluate(args);
				PackedCollection<Scalar> out = Scalar.scalarBank(tot);

				ev.into(out.range(shape(tot - 2, 2), 2).traverse(1)).evaluate(
						data.range(shape(tot - 2, 2), 2).traverse(1),
						data.range(shape(tot - 2, 2), 3).traverse(1));
				out.set(0, data.valueAt(0, 0) *  data.valueAt(0, 0), 1.0);
				out.set(tot - 1, data.valueAt(0, 1) * data.valueAt(0, 1), 1.0);
				return out;
			};
		};
	}
}
