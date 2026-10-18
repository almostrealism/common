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

/**
 * Abstract base class providing common functionality for {@link CollectionExpression}
 * implementations, including shape management and naming.
 *
 * <p>{@link CollectionExpressionAdapter} serves as the foundation for most concrete
 * collection expression types in the framework. It extends {@link CollectionExpressionBase}
 * to provide:
 * <ul>
 *   <li><b>Shape storage</b> - Maintains the {@link TraversalPolicy} that defines
 *       the collection's dimensions and traversal behavior</li>
 *   <li><b>Total shape context</b> - Optionally stores a larger shape context when
 *       this expression represents a subset of a larger collection</li>
 *   <li><b>Naming support</b> - Associates a descriptive name with the expression
 *       for debugging and description purposes</li>
 * </ul>
 *
 * <h2>Subclassing Guidelines</h2>
 *
 * <p>Concrete implementations must:
 * <ol>
 *   <li>Call the constructor with a name (may be null) and a non-null shape</li>
 *   <li>Implement {@link TraversableExpression#getValueAt(Expression)} to define
 *       how values are computed at each index</li>
 * </ol>
 *
 * <h2>Example Implementation</h2>
 *
 * <pre>{@code
 * public class MyExpression extends CollectionExpressionAdapter {
 *     public MyExpression(TraversalPolicy shape) {
 *         super("MyExpression", shape);
 *     }
 *
 *     @Override
 *     public Expression<Double> getValueAt(Expression<?> index) {
 *         // Compute and return the value expression at the given index
 *         return index.multiply(e(2.0));
 *     }
 * }
 * }</pre>
 *
 * @see CollectionExpressionBase
 * @see CollectionExpression
 * @see TraversalPolicy
 * @author Michael Murray
 */
public abstract class CollectionExpressionAdapter extends CollectionExpressionBase {
	/**
	 * The optional descriptive name for this collection expression.
	 * Used in {@link #describe()} output for debugging purposes.
	 * May be null if no name was provided.
	 */
	private final String name;

	/**
	 * The shape defining the dimensions and traversal policy of this collection.
	 * This field is never null after construction.
	 */
	private final TraversalPolicy shape;

	/**
	 * The optional total shape context when this expression represents a
	 * subset of a larger collection. May be null if not set.
	 *
	 * @see #setTotalShape(TraversalPolicy)
	 * @see #getTotalShape()
	 */
	private TraversalPolicy totalShape;

	/**
	 * Constructs a new {@link CollectionExpressionAdapter} with the specified name and shape.
	 *
	 * @param name the descriptive name for this expression, may be null
	 * @param shape the {@link TraversalPolicy} defining the dimensions and traversal
	 *              behavior; must not be null
	 * @throws IllegalArgumentException if shape is null
	 */
	public CollectionExpressionAdapter(String name, TraversalPolicy shape) {
		if (shape == null) {
			throw new IllegalArgumentException("Shape is required");
		}

		this.name = name;
		this.shape = shape;
	}

	/**
	 * Returns the shape of this collection expression.
	 *
	 * @return the {@link TraversalPolicy} defining this collection's dimensions
	 */
	@Override
	public TraversalPolicy getShape() { return shape; }

	/**
	 * Sets the total shape context for this collection expression.
	 *
	 * <p>This method stores a reference to a larger collection's shape when this
	 * expression represents a subset or view of that larger collection. The total
	 * shape can be used by subclasses to make optimization decisions.
	 *
	 * @param shape the {@link TraversalPolicy} of the larger collection
	 */
	@Override
	public void setTotalShape(TraversalPolicy shape) {
		this.totalShape = shape;
	}

	/**
	 * Returns the total shape context if one has been set.
	 *
	 * @return the total shape {@link TraversalPolicy}, or null if not set
	 * @see #setTotalShape(TraversalPolicy)
	 */
	protected TraversalPolicy getTotalShape() {
		return totalShape;
	}

	/**
	 * Returns a human-readable description of this collection expression.
	 *
	 * <p>The description includes the expression's name (if set) followed by
	 * the shape description from the superclass.
	 *
	 * @return a string containing the name and shape description
	 */
	@Override
	public String describe() {
		return name + " " + super.describe();
	}
}
