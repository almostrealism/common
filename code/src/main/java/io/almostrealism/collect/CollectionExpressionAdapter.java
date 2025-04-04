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
import io.almostrealism.kernel.DefaultIndex;

public abstract class CollectionExpressionAdapter extends CollectionExpressionBase {
	protected static long idxCount;

	private final String name;
	private final TraversalPolicy shape;
	private TraversalPolicy totalShape;

	public CollectionExpressionAdapter(String name, TraversalPolicy shape) {
		if (shape == null) {
			throw new IllegalArgumentException("Shape is required");
		}

		this.name = name;
		this.shape = shape;
	}

	@Override
	public TraversalPolicy getShape() { return shape; }

	@Override
	public void setTotalShape(TraversalPolicy shape) {
		this.totalShape = shape;
	}

	protected TraversalPolicy getTotalShape() {
		return totalShape;
	}

	@Override
	public String describe() {
		return name + " " + super.describe();
	}

	public static <T> Expression<?> simplify(int threshold, Expression<T> e) {
		if (e.countNodes() > threshold) {
			return e.simplify();
		}

		return e;
	}

	public static DefaultIndex generateTemporaryIndex() {
		return new DefaultIndex("ci_" + idxCount++);
	}

	public static DefaultIndex generateTemporaryIndex(int limit) {
		return new DefaultIndex("ci_" + idxCount++, limit);
	}
}
