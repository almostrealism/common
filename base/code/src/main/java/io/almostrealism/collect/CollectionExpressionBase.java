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

/**
 * Abstract base class for {@link CollectionExpression} implementations that provides
 * default implementations for common shape transformation operations. This class
 * simplifies the creation of collection expressions by handling reshaping and
 * traversal through uniform wrapper expressions.
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>{@link #delta(CollectionExpression)} - Creates a delta expression for computing differences</li>
 *   <li>{@link #reshape(TraversalPolicy)} - Wraps this expression with a new shape</li>
 *   <li>{@link #traverse(int)} - Wraps this expression with a different traversal axis</li>
 * </ul>
 *
 * <p>Subclasses must implement {@link CollectionExpression#getShape()} and
 * {@link TraversableExpression#getValueAt(io.almostrealism.expression.Expression)}
 * to provide the actual expression evaluation logic.</p>
 *
 * @see CollectionExpression
 * @see UniformCollectionExpression
 * @see DeltaCollectionExpression
 */
public abstract class CollectionExpressionBase implements CollectionExpression<CollectionExpressionBase> {

	/**
	 * Creates a delta expression that computes the difference between this
	 * expression and the target expression.
	 *
	 * @param target the target collection expression to compare against
	 * @return a new {@link DeltaCollectionExpression} representing the delta
	 */
	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return new DeltaCollectionExpression(this, target);
	}

	/**
	 * Reshapes this expression to the specified shape by wrapping it in a
	 * {@link UniformCollectionExpression} that applies identity transformation
	 * with the new shape.
	 *
	 * @param shape the new {@link TraversalPolicy} to apply
	 * @return a new expression with the specified shape
	 */
	@Override
	public CollectionExpressionBase reshape(TraversalPolicy shape) {
		return new UniformCollectionExpression("reshape", shape, e -> e[0], this);
	}

	/**
	 * Changes the traversal axis of this expression by wrapping it in a
	 * {@link UniformCollectionExpression} with the traversed shape.
	 *
	 * @param axis the new traversal axis
	 * @return a new expression with the modified traversal axis
	 */
	@Override
	public CollectionExpressionBase traverse(int axis) {
		return new UniformCollectionExpression("traverse", getShape().traverse(axis), e -> e[0], this);
	}
}
