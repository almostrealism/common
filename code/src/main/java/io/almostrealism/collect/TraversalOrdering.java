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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;

import java.util.OptionalInt;

public interface TraversalOrdering extends IndexSet {
	Expression<Integer> indexOf(Expression<Integer> idx);

	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		return indexOf(index).greaterThanOrEqual(0);
	}

	default int indexOf(int idx) {
		return indexOf(new IntegerConstant(idx)).intValue().orElseThrow();
	}

	default OptionalInt getLength() {
		return OptionalInt.empty();
	}

	default TraversalOrdering compose(TraversalOrdering other) {
		if (other == null) {
			return this;
		} else {
			return new DelegatedTraversalOrdering(this, other);
		}
	}
}
