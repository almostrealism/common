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

package org.almostrealism.optimize;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.stream.IntStream;

public class NegativeLogLikelihood implements LossProvider {
	@Override
	public double loss(PackedCollection<?> output, PackedCollection<?> target) {
		return -output.toDouble(target.argmax());
	}

	@Override
	public Producer<PackedCollection<?>> gradient(Producer<PackedCollection<?>> output,
												  Producer<PackedCollection<?>> target) {
		return () -> {
			Evaluable<PackedCollection<?>> out = output.get();
			Evaluable<PackedCollection<?>> valid = target.get();

			return args -> {
				PackedCollection<?> o = out.evaluate(args);
				PackedCollection<?> v = valid.evaluate(args);

				return PackedCollection.of(IntStream.range(0, o.getShape().getTotalSize()).mapToDouble(i -> {
					if (i == v.argmax()) {
						return -1.0;
					} else {
						return 0.0;
					}
				}).toArray());
			};
		};
	}
}
