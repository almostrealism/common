/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.kernel;

import java.util.OptionalLong;

public interface SequenceGenerator {
	default OptionalLong getLimit() {
		OptionalLong upperBound = upperBound(null);
		if (upperBound.isEmpty()) return OptionalLong.empty();
		return OptionalLong.of(upperBound.getAsLong() + 1);
	}

	default OptionalLong upperBound() { return upperBound(null); }
	default OptionalLong lowerBound() { return lowerBound(null); }

	OptionalLong upperBound(KernelStructureContext context);
	OptionalLong lowerBound(KernelStructureContext context);

	Number value(IndexValues indexValues);

	default IndexSequence sequence(Index index, long len) { return sequence(index, len, len); }
	IndexSequence sequence(Index index, long len, long limit);
}
