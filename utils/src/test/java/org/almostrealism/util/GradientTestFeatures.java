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

package org.almostrealism.util;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

public interface GradientTestFeatures {
	default Producer<PackedCollection<?>> applyGradient(CollectionProducer<?> delta,
														CollectionProducer<?> gradient) {
		CollectionFeatures cf = CollectionFeatures.getInstance();
		int outSize = cf.shape(gradient).getTotalSize();
		int inSize = cf.shape(delta).getTotalSize() / outSize;
		return delta.reshape(outSize, inSize)
				.traverse(1)
				.multiply(gradient.reshape(outSize).traverse(1).repeat(inSize))
				.enumerate(1, 1)
				.sum(1)
				.reshape(cf.shape(inSize))
				.each();
	}
}
