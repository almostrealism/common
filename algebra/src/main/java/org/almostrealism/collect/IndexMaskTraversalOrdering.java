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

package org.almostrealism.collect;

import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;

import java.util.OptionalInt;

public class IndexMaskTraversalOrdering implements TraversalOrdering {
	private PackedCollection<?> mask;

	public IndexMaskTraversalOrdering(PackedCollection<?> mask) {
		this.mask = mask;
	}

	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		int i = idx.intValue().orElseThrow();
		double m[] = mask.toArray();

		for (int k = 0; k < m.length; k++) {
			if (((int) m[k]) == i) {
				return new IntegerConstant(k);
			}
		}

		return new IntegerConstant(-1);
	}

	@Override
	public OptionalInt getLength() {
		return OptionalInt.of(mask.getMemLength());
	}
}
