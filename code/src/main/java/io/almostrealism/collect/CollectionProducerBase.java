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

package io.almostrealism.collect;

import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;

public interface CollectionProducerBase<T, P extends Producer<T>> extends Producer<T>, Shape<P>, Countable {
	@Override
	default long getCountLong() { return getShape().getCountLong(); }

	@Override
	default String describe() {
		return getClass().getSimpleName() + " " +
				getCountLong() + "x" +
				(isFixedCount() ? " (fixed) " : " (variable) ") +
				getShape().toString();
	}
}
