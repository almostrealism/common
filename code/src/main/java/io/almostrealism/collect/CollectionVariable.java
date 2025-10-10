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

public class CollectionVariable<T extends Collection<Double, ? extends Collection<?, ?>>>
		extends ArrayVariable<T> implements CollectionExpression<CollectionVariable<T>> {

	private TraversalPolicy shape;

	public CollectionVariable(String name, TraversalPolicy shape,
							  Supplier<Evaluable<? extends Multiple<T>>> producer) {
		this(name, shape, PhysicalScope.GLOBAL, Double.class, producer);
	}

	public CollectionVariable(String name, TraversalPolicy shape,
							  PhysicalScope scope, Class<?> type,
							  Supplier<Evaluable<? extends Multiple<T>>> p) {
		super(name, scope, type, p);
		this.shape = shape;
	}

	public TraversalPolicy getShape() { return shape; }

	public boolean isFixedCount() {
		Supplier p = getProducer();
		if (p instanceof Delegated) {
			p = (Producer) ((Delegated<?>) p).getDelegate();
		}

		return Countable.isFixedCount(p);
	}

	@Override
	public CollectionVariable<T> reshape(TraversalPolicy shape) {
		throw new UnsupportedOperationException();
	}

	@Override
	public CollectionVariable<T> traverse(int axis) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Expression<Integer> length() {
		if (isFixedCount() && getShape().getSize() != 1) {
			return e(getShape().getSize());
		}

		return super.length();
	}

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

	@Override
	public String describe() {
		Supplier<?> p = getProducer();

		if (p instanceof Shape) {
			return super.describe();
		} else {
			return super.describe() + " " + getShape().toStringDetail();
		}
	}

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
