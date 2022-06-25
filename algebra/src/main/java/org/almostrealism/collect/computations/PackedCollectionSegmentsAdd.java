/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.computations.Repeated;

import java.util.function.Supplier;

public class PackedCollectionSegmentsAdd extends Repeated {
	public PackedCollectionSegmentsAdd(Producer<PackedCollection> destination,
									   Producer<PackedCollection> data,
									   Producer<PackedCollection> offsets,
									   Producer<PackedCollection> count) {
		super((Supplier) destination, (Supplier) data, (Supplier) offsets, (Supplier) count);
	}

	public ArrayVariable getDestination() { return getArgument(0); }
	public ArrayVariable getData() { return getArgument(1); }
	public ArrayVariable getOffsets() { return getArgument(2); }
	public ArrayVariable getCount() { return getArgument(3, 1); }

	@Override
	public String getInner(Expression<?> index) {
		return getDestination().valueAt(0).getExpression() + " = " +
				new Sum(getDestination().valueAt(0),
						getData().valueAt(getOffsets().valueAt(index))).getExpression();
	}

	@Override
	public String getCondition(Expression<?> index) {
		return index.getExpression() + " < " + getCount().valueAt(0).getExpression();
	}
}
