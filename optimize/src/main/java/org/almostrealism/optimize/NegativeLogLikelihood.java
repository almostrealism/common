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
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;

import java.util.stream.IntStream;

public class NegativeLogLikelihood implements LossProvider, CollectionFeatures {
	@Override
	public double loss(PackedCollection<?> output, PackedCollection<?> target) {
		PackedCollection<?> o = output.reshape(padDimensions(output.getShape(), 2)).traverse(1);
		PackedCollection<?> t = target.reshape(padDimensions(target.getShape(), 2)).traverse(1);

		int bs = o.getShape().length(0);
		if (bs != t.getShape().length(0)) {
			throw new IllegalArgumentException("Batch size mismatch");
		}

		return IntStream.range(0, bs).mapToDouble(i -> {
			PackedCollection<?> v = (PackedCollection<?>) t.get(i);
			return -o.get(i).toDouble(v.argmax());
		}).average().orElse(0.0);
	}

	@Override
	public Producer<PackedCollection<?>> gradient(Producer<PackedCollection<?>> output,
												  Producer<PackedCollection<?>> target) {
		return () -> {
			Evaluable<PackedCollection<?>> out = output.get();
			Evaluable<PackedCollection<?>> valid = target.get();

			return args -> {
				PackedCollection<?> o = out.evaluate(args).traverse(1);
				PackedCollection<?> v = valid.evaluate(args).traverse(1);

				int bs = o.getShape().length(0);
				double grad[] = new double[o.getShape().getTotalSize()];

				for (int n = 0; n < o.getShape().length(0); n++) {
					double od[] = o.get(n).toArray();
					int idx = ((PackedCollection<?>) v.get(n)).argmax();

					for (int i = 0; i < od.length; i++) {
						if (i == idx) {
							grad[n * bs + i] = -1.0;
						} else {
							grad[n * bs +i] = 0.0;
						}
					}
				}

				return PackedCollection.of(grad).reshape(o.getShape());
			};
		};
	}
}
