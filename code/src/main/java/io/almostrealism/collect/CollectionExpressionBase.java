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

public abstract class CollectionExpressionBase implements CollectionExpression<CollectionExpressionBase> {
	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return new DeltaCollectionExpression(this, target);
	}

	@Override
	public CollectionExpressionBase reshape(TraversalPolicy shape) {
		return new UniformCollectionExpression(shape, e -> e[0], this);
	}

	@Override
	public CollectionExpressionBase traverse(int axis) {
		return new UniformCollectionExpression(getShape().traverse(axis), e -> e[0], this);
	}
}
