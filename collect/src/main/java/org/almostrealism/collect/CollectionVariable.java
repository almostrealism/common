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

package org.almostrealism.collect;

import io.almostrealism.code.ExpressionList;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class CollectionVariable<T extends Shape> extends ArrayVariable<T> implements TraversableExpression<Double> {
	private TraversalPolicy shape;

	private CollectionVariable<T> parent;
	private Expression pos[];

	public CollectionVariable(NameProvider np, String name, TraversalPolicy shape, Supplier<Evaluable<? extends T>> producer) {
		this(np, name, shape, np.getDefaultPhysicalScope(), (Class<T>) Shape.class, producer);
	}

	public CollectionVariable(NameProvider np, String name, TraversalPolicy shape,
							  PhysicalScope scope, Class<T> type, Supplier<Evaluable<? extends T>> p) {
		super(np, name, scope, type, p);
		this.shape = shape;
	}

	protected CollectionVariable(TraversalPolicy shape, CollectionVariable<T> parent, Expression... pos) {
		super(null, null, (Expression<Integer>) null);
		this.shape = shape;
		this.parent = parent;
		this.pos = pos;
	}

	public TraversalPolicy getShape() { return shape; }

	@Override
	public InstanceReference<T> get(String index, int kernelIndex, Variable... dependencies) {
		if (parent == null) {
			return super.get(index, kernelIndex, dependencies);
		} else {
			Expression idx = new Expression(Integer.class, index, dependencies);
			Expression<?> p = parent.getShape().subset(getShape(), idx, pos);
			return parent.get(p, -1);
		}
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

		if (producer instanceof TraversableExpression) {
			return ((TraversableExpression<Double>) producer).getValueAt(index);
		} else {
			return (Expression) get(index, -1);
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
			p[i] = new Expression(Integer.class, String.valueOf(pos[i]));
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

	public Stream<Expression<Double>> stream() {
		return IntStream.range(0, shape.getTotalSize()).mapToObj(i -> getValueAt(e(i)));
	}

	public ExpressionList<T> toList() {
		return stream().collect(ExpressionList.collector());
	}

	public Expression<T> sum() { return toList().sum(); }

	public Expression<T> max() { return toList().max(); }

	public ExpressionList<T> exp() { return toList().exp(); }

	public ExpressionList<T> multiply(CollectionVariable<T> operands) {
		ExpressionList<T> a = stream().collect(ExpressionList.collector());
		ExpressionList<T> b = operands.stream().collect(ExpressionList.collector());
		return a.multiply(b);
	}

	public static <T> ArrayVariable<T> create(NameProvider np, String name, Supplier<Evaluable<? extends T>> p) {
		if (p instanceof Shape) {
			return new CollectionVariable(np, name, ((Shape) p).getShape(), p);
		} else if (p instanceof Delegated && ((Delegated) p).getDelegate() instanceof Shape) {
			return new CollectionVariable(np, name, ((Shape) ((Delegated) p).getDelegate()).getShape(), p);
		} else {
			return new ArrayVariable<>(np, name, p);
		}
	}
}
