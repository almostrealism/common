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

import java.util.OptionalInt;

public class DefaultTraversalOrdering implements TraversalOrdering {
	private OptionalInt length;

	public DefaultTraversalOrdering() {
		this.length = OptionalInt.empty();
	}

	public DefaultTraversalOrdering(int length) {
		this.length = OptionalInt.of(length);
	}

	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		return idx;
	}

	@Override
	public OptionalInt getLength() {
		return length;
	}
}
