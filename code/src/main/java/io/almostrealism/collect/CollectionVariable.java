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

import io.almostrealism.code.NameProvider;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Multiple;

import java.util.function.Supplier;

public class CollectionVariable<T extends Collection<Double, ? extends Collection<?, ?>>>
		extends ArrayVariable<T> implements CollectionExpression<CollectionVariable<T>> {
	public static boolean enableAbsoluteValueAt = false;

	private TraversalPolicy shape;

	private CollectionVariable<T> parent;
	private Expression pos[];

	public CollectionVariable(NameProvider np, String name, TraversalPolicy shape,
							  Supplier<Evaluable<? extends Multiple<T>>> producer) {
		this(np, name, shape,
				np == null ? null : np.getDefaultPhysicalScope(),
				Double.class, producer);
	}

	public CollectionVariable(NameProvider np, String name, TraversalPolicy shape,
							  PhysicalScope scope, Class<?> type,
							  Supplier<Evaluable<? extends Multiple<T>>> p) {
		super(np, name, scope, type, p);
		this.shape = shape;
	}

	protected CollectionVariable(TraversalPolicy shape, CollectionVariable<T> parent, Expression... pos) {
		super(null, null, null, null);
		this.shape = shape;
		this.parent = parent;
		this.pos = pos;
	}

	public TraversalPolicy getShape() { return shape; }

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
		return getShape().getSize() == 1 ? super.length() : e(getShape().getSize());
	}

	@Override
	public Expression<T> referenceRelative(Expression<?> idx) {
		if (parent != null) {
			Expression<?> p = parent.getShape().subset(getShape(), idx, pos);
			return parent.reference(p, false);
		}

		return super.referenceRelative(idx);
	}

	@Override
	public Expression<T> valueAt(Expression<?> exp) {
		if (enableAbsoluteValueAt)
			return (Expression) getValueAt(exp);

		return super.valueAt(exp);
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		if (parent != null) {
			Expression<?> index = getShape().index(pos);
			return parent.getValue(parent.getShape().subset(getShape(), index, this.pos));
		}

		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (parent != null) {
			Expression<?> p = parent.getShape().subset(getShape(), index, pos);
			return parent.getValueAt(p);
		}

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
			return (Expression) reference(e(0), false);
		} else {
			if (getShape().getSize() != 1 || fixedCount) {
				index = index.toInt().imod(getShape().getTotalSize());
			}

			if (getShape().getOrder() != null) {
				index = getShape().getOrder().indexOf(index);
			}

			return (Expression) reference(index, false);
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

	public Expression<Double> get(Expression<?>... pos) {
		return getValue(pos);
	}

	public CollectionVariable<T> get(TraversalPolicy shape, int... pos) {
		// TODO  This can be made more efficient by converting pos[] into an index ahead of time

		Expression[] p = new Expression[pos.length];
		for (int i = 0; i < pos.length; i++) {
			p[i] = new IntegerConstant(pos[i]);
		}
		return get(shape, p);
	}

	public CollectionVariable<T> get(TraversalPolicy shape, Expression... pos) {
		if (shape.getDimensions() != this.shape.getDimensions()) {
			System.out.println("WARN: Obtaining a " + shape.getDimensions() +
					"d subset of a " + this.shape.getDimensions() +
					"d collection is likely to produce an unexpected result");
		}

		return new CollectionVariable<>(shape, this, pos);
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

	public static <T> ArrayVariable<T> create(NameProvider np, String name,
											  Supplier<Evaluable<? extends Multiple<T>>> p) {
		if (p instanceof Shape) {
			return new CollectionVariable(np, name, ((Shape) p).getShape(), p);
		} else if (p instanceof Delegated && ((Delegated) p).getDelegate() instanceof Shape) {
			return new CollectionVariable(np, name, ((Shape) ((Delegated) p).getDelegate()).getShape(), p);
		} else {
			return new ArrayVariable<>(np, name, p);
		}
	}
}
