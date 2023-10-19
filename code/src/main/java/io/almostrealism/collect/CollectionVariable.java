/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;

import java.util.function.Supplier;

public class CollectionVariable<T extends Shape> extends ArrayVariable<T> implements CollectionExpression {
	public static boolean enableAbsoluteValueAt = false;

	private TraversalPolicy shape;

	private CollectionVariable<T> parent;
	private Expression pos[];

	public CollectionVariable(LanguageOperations lang, NameProvider np, String name, TraversalPolicy shape, Supplier<Evaluable<? extends T>> producer) {
		this(lang, np, name, shape, np.getDefaultPhysicalScope(), (Class<T>) Shape.class, producer);
	}

	public CollectionVariable(LanguageOperations lang, NameProvider np, String name, TraversalPolicy shape,
							  PhysicalScope scope, Class<T> type, Supplier<Evaluable<? extends T>> p) {
		super(lang, np, name, scope, type, p);
		this.shape = shape;
	}

	protected CollectionVariable(LanguageOperations lang, TraversalPolicy shape, CollectionVariable<T> parent, Expression... pos) {
		super(lang, null, null, (Expression<Integer>) null);
		this.shape = shape;
		this.parent = parent;
		this.pos = pos;
	}

	public TraversalPolicy getShape() { return shape; }

	@Override
	public Expression<Integer> length() {
		return getShape().getSize() == 1 ? super.length() : e(getShape().getSize());
	}

	@Override
	public InstanceReference<T> referenceRelative(Expression<?> idx) {
		if (parent != null) {
			Expression<?> p = parent.getShape().subset(getShape(), idx, pos);
			return parent.reference(p);
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

		if (getShape().getTotalSize() == 1) {
			return (Expression) reference(e(0));
		} else {
//			index = new Mod(new Cast("int", index), e(getShape().getTotalSize()), false);
			index = index.toInt().mod(e(getShape().getTotalSize()), false);
			return (Expression) reference(index);
		}
	}

	public Expression<Double> get(Expression<?>... pos) {
		// return get(getShape().index(pos), -1);
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

		return new CollectionVariable<>(getLanguage(), shape, this, pos);
	}

	public static <T> ArrayVariable<T> create(LanguageOperations lang, NameProvider np,
											  String name, Supplier<Evaluable<? extends T>> p) {
		if (p instanceof Shape) {
			return new CollectionVariable(lang, np, name, ((Shape) p).getShape(), p);
		} else if (p instanceof Delegated && ((Delegated) p).getDelegate() instanceof Shape) {
			return new CollectionVariable(lang, np, name, ((Shape) ((Delegated) p).getDelegate()).getShape(), p);
		} else {
			return new ArrayVariable<>(lang, np, name, p);
		}
	}
}
