/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.stats;

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Random;

public interface DistributionFeatures extends CollectionFeatures {
	Random rand = new Random();

	default int sample(PackedCollection<?> distribution) {
		return sample(distribution, distribution.getMemLength());
	}

	default int sample(PackedCollection<?> distribution, int n) {
		double probabilities[] = distribution.toArray(0, n);
		double r = rand.nextDouble();
		double d = 0.0;

		for (int i = 0; i < n; i++) {
			d += probabilities[i];

			if (r < d) {
				return i;
			}
		}

		return n - 1;
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> softmax(CollectionProducer<T> input) {
		return softmax(input, true);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> softmax(CollectionProducer<T> input, boolean subtractMax) {
		int size = shape(input).getSize();
		CollectionProducer<PackedCollection<?>> o = (CollectionProducer) input;

		if (subtractMax) {
			o = o.max();
			o = o.expand(size);
			o = input.traverse().subtractIgnoreZero(o);
		}

		o = o.expIgnoreZero().consolidate();
		o = o.divide(o.sum().expand(size));
		return (CollectionProducer) o;
	}
}
