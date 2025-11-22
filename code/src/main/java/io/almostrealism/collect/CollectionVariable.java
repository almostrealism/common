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

package io.almostrealism.collect;

import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Multiple;

import java.util.function.Supplier;

/**
 * A specialized {@link ArrayVariable} that represents a collection of values with a defined
 * {@link TraversalPolicy} shape. This class bridges the gap between array-based variable
 * representations and collection-based expressions, enabling shape-aware indexing and
 * traversal operations.
 *
 * <p>{@code CollectionVariable} extends {@link ArrayVariable} to provide collection-specific
 * functionality including:</p>
 * <ul>
 *   <li>Shape-aware value access through {@link #getValueAt(Expression)}</li>
 *   <li>Support for {@link TraversableExpression} producers</li>
 *   <li>Index ordering transformations based on the shape's traversal order</li>
 *   <li>Fixed count detection for optimization purposes</li>
 * </ul>
 *
 * <p><b>Note:</b> The generics in this class have known issues. {@link ArrayVariable}&lt;T&gt;
 * is a Variable&lt;Multiple&lt;T&gt;&gt;, but this class assumes T is the collection, resulting
 * in a redundant type Multiple&lt;Collection&lt;Double&gt;&gt;.</p>
 *
 * @param <T> the type of collection, extending {@link Collection} with Double elements
 *
 * @see ArrayVariable
 * @see CollectionExpression
 * @see TraversalPolicy
 */
// TODO  The generics here are wrong, because ArrayVariable<T> is a Variable<Multiple<T>>,
// TODO  but this class assumes that T is the collection - resulting in the redundant type
// TODO  Multiple<Collection<Double>>
public class CollectionVariable<T extends Collection<Double, ? extends Collection<?, ?>>>
		extends ArrayVariable<T> implements CollectionExpression<CollectionVariable<T>> {

	/**
	 * Global flag to enable or disable the {@link #valueAt(Expression)} method's delegation
	 * to {@link #getValueAt(Expression)}. When enabled, value access will use the
	 * collection-aware indexing logic.
	 */
	public static boolean enableValueAt = false;

	/**
	 * The shape policy defining the structure and traversal behavior of this collection variable.
	 */
	private TraversalPolicy shape;

	/**
	 * Constructs a new {@code CollectionVariable} with global physical scope and Double type.
	 *
	 * @param name     the name identifier for this variable
	 * @param shape    the {@link TraversalPolicy} defining the shape of this collection
	 * @param producer the supplier that produces evaluable instances of this collection
	 */
	public CollectionVariable(String name, TraversalPolicy shape,
							  Supplier<Evaluable<? extends Multiple<T>>> producer) {
		this(name, shape, PhysicalScope.GLOBAL, Double.class, producer);
	}

	/**
	 * Constructs a new {@code CollectionVariable} with specified scope and type.
	 *
	 * @param name  the name identifier for this variable
	 * @param shape the {@link TraversalPolicy} defining the shape of this collection
	 * @param scope the {@link PhysicalScope} specifying where this variable resides
	 * @param type  the element type class (typically {@code Double.class})
	 * @param p     the supplier that produces evaluable instances of this collection
	 */
	public CollectionVariable(String name, TraversalPolicy shape,
							  PhysicalScope scope, Class<?> type,
							  Supplier<Evaluable<? extends Multiple<T>>> p) {
		super(name, scope, type, p);
		this.shape = shape;
	}

	/**
	 * Returns the {@link TraversalPolicy} that defines the shape of this collection.
	 *
	 * @return the shape policy for this collection variable
	 */
	public TraversalPolicy getShape() { return shape; }

	/**
	 * Determines whether this collection variable has a fixed element count.
	 * This method checks if the underlying producer implements {@link Countable}
	 * and reports a fixed count, unwrapping any delegation layers if present.
	 *
	 * @return {@code true} if the element count is fixed and known at compile time,
	 *         {@code false} otherwise
	 */
	public boolean isFixedCount() {
		Supplier p = getProducer();
		if (p instanceof Delegated) {
			p = (Producer) ((Delegated<?>) p).getDelegate();
		}

		return Countable.isFixedCount(p);
	}

	/**
	 * Reshaping is not supported for {@code CollectionVariable} instances.
	 *
	 * @param shape the new shape to apply
	 * @return never returns normally
	 * @throws UnsupportedOperationException always thrown
	 */
	@Override
	public CollectionVariable<T> reshape(TraversalPolicy shape) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Traversal axis modification is not supported for {@code CollectionVariable} instances.
	 *
	 * @param axis the axis to traverse
	 * @return never returns normally
	 * @throws UnsupportedOperationException always thrown
	 */
	@Override
	public CollectionVariable<T> traverse(int axis) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns an expression representing the length of this collection.
	 * If the collection has a fixed count and the shape size is not 1,
	 * returns the total size from the shape. Otherwise, delegates to the
	 * superclass implementation.
	 *
	 * @return an {@link Expression} representing the length
	 */
	@Override
	public Expression<Integer> length() {
		if (isFixedCount() && getShape().getSize() != 1) {
			return e(getShape().getSize());
		}

		return super.length();
	}

	/**
	 * Returns an expression for accessing the value at a given index expression.
	 * If {@link #enableValueAt} is set to {@code true}, this method delegates to
	 * {@link #getValueAt(Expression)} for collection-aware indexing.
	 *
	 * @param exp the index expression
	 * @return an expression representing the value at the specified index
	 */
	@Override
	public Expression<T> valueAt(Expression<?> exp) {
		if (enableValueAt) {
			return (Expression) getValueAt(exp);
		}

		return super.valueAt(exp);
	}

	/**
	 * Returns an expression for accessing the Double value at a given index.
	 * This method provides collection-aware indexing by:
	 * <ul>
	 *   <li>Delegating to the underlying {@link TraversableExpression} if the producer supports it</li>
	 *   <li>Handling single-element shapes specially</li>
	 *   <li>Applying modular arithmetic for fixed-count collections</li>
	 *   <li>Transforming indices according to the shape's traversal order</li>
	 * </ul>
	 *
	 * @param index the index expression specifying which element to access
	 * @return an expression representing the Double value at the specified index
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		Supplier producer = getProducer();

		if (producer instanceof Delegated) {
			producer = (Producer) ((Delegated<?>) producer).getDelegate();
		}

		// TODO  This process of falling back when TraversableExpression
		// TODO  returns null shouldn't really be necessary, but there
		// TODO  are currently some implementations of TraversableExpression
		// TODO  that wrap other types, and it isn't known whether those
		// TODO  types are TraversableExpressions or not.
		Expression<Double> result = null;

		if (producer instanceof TraversableExpression) {
			result = ((TraversableExpression<Double>) producer).getValueAt(index);
		}

		if (result != null) return result;

		boolean fixedCount = Countable.isFixedCount(getProducer());

		if (getShape().getTotalSize() == 1 && fixedCount) {
			return (Expression) reference(e(0));
		} else {
			if (getShape().getSize() != 1 || fixedCount) {
				index = index.toInt().imod(getShape().getTotalSize());
			}

			if (getShape().getOrder() != null) {
				index = getShape().getOrder().indexOf(index);
			}

			return (Expression) reference(index);
		}
	}

	/**
	 * Returns an expression for accessing a value at a relative index offset.
	 * Delegates to the superclass implementation.
	 *
	 * @param index the relative index expression
	 * @return an expression representing the value at the relative index
	 */
	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return super.getValueRelative(index);
	}

	/**
	 * Computes a unique non-zero offset for the given indices, if one exists.
	 * This method checks if the underlying producer is a {@link TraversableExpression}
	 * and delegates to its offset calculation, otherwise falls back to the default
	 * {@link CollectionExpression} implementation.
	 *
	 * @param globalIndex the global kernel index
	 * @param localIndex  the local index within a work group
	 * @param targetIndex the target index expression to evaluate
	 * @return an expression representing the unique non-zero offset, or {@code null}
	 */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		Supplier producer = getProducer();

		if (producer instanceof Delegated) {
			producer = (Producer) ((Delegated<?>) producer).getDelegate();
		}

		if (producer instanceof TraversableExpression) {
			return ((TraversableExpression) producer).uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
		}

		return CollectionExpression.super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	/**
	 * Determines whether this collection variable algebraically matches another expression.
	 * Two collection variables match if their underlying producers are both {@link Algebraic}
	 * and report a match. Otherwise, falls back to the default {@link CollectionExpression}
	 * matching logic.
	 *
	 * @param other the other algebraic expression to compare against
	 * @param <A>   the type of algebraic expression
	 * @return {@code true} if the expressions match algebraically, {@code false} otherwise
	 */
	@Override
	public <A extends Algebraic> boolean matches(A other) {
		if (other instanceof CollectionVariable) {
			Supplier<?> a = getProducer();
			Supplier<?> b = ((CollectionVariable) other).getProducer();
			if (a instanceof Algebraic && b instanceof Algebraic) {
				return ((Algebraic) a).matches((Algebraic) b);
			}
		}

		return CollectionExpression.super.matches(other);
	}

	/**
	 * Returns a human-readable description of this collection variable.
	 * If the underlying producer implements {@link Shape}, returns the default
	 * description. Otherwise, appends the shape details to the description.
	 *
	 * @return a string description of this collection variable
	 */
	@Override
	public String describe() {
		Supplier<?> p = getProducer();

		if (p instanceof Shape) {
			return super.describe();
		} else {
			return super.describe() + " " + getShape().toStringDetail();
		}
	}

	/**
	 * Factory method to create an appropriate array variable for the given producer.
	 * If the producer or its delegate implements {@link Shape}, creates a
	 * {@code CollectionVariable} with the corresponding shape. Otherwise, creates
	 * a standard {@link ArrayVariable}.
	 *
	 * @param name the name identifier for the variable
	 * @param p    the supplier that produces evaluable instances
	 * @param <T>  the element type
	 * @return a new {@code CollectionVariable} if the producer has shape information,
	 *         otherwise a standard {@code ArrayVariable}
	 */
	public static <T> ArrayVariable<T> create(String name,
											  Supplier<Evaluable<? extends Multiple<T>>> p) {
		if (p instanceof Shape) {
			return new CollectionVariable(name, ((Shape) p).getShape(), p);
		} else if (p instanceof Delegated && ((Delegated) p).getDelegate() instanceof Shape) {
			return new CollectionVariable(name, ((Shape) ((Delegated) p).getDelegate()).getShape(), p);
		} else {
			return new ArrayVariable<>(name, p);
		}
	}
}
