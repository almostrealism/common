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

import java.util.Optional;

/**
 * A subset of the infinite set of integers which may
 * or may not itself by finite. {@link IndexSet}s
 * must at least define set membership, but are not
 * required to provide anything beyond that.
 *
 * @author  Michael Murray
 */
public interface IndexSet {
	/**
	 * Determine if the provided index is a member of this {@link IndexSet},
	 * if it is possible to determine.
	 */
	default Optional<Boolean> containsIndex(int index) {
		return containsIndex(new IntegerConstant(index)).booleanValue();
	}

	/**
	 * Create an {@link Expression} representing the status of the provided index,
	 * as either a member of this {@link IndexSet} or not.
	 */
	Expression<Boolean> containsIndex(Expression<Integer> index);
}
