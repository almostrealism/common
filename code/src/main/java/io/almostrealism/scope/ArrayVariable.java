/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.scope;

import io.almostrealism.code.Array;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Evaluable;

import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class ArrayVariable<T> extends Variable<T, ArrayVariable<T>> implements Array<T, ArrayVariable<T>> {
	public static BiFunction<String, String, String> dereference = (name, pos) -> name + "[" + pos + "]";

	private final NameProvider names;

	private int delegateOffset;
	private Expression<Integer> arraySize;

	public ArrayVariable(NameProvider np, String name, Expression<Integer> arraySize) {
		super(name, true, (Expression) null);
		this.names = np;
		setArraySize(arraySize);
	}

	public ArrayVariable(NameProvider np, String name, Supplier<Evaluable<? extends T>> producer) {
		this(np, name, np.getDefaultPhysicalScope(), (Class<T>) Double.class, producer);
	}

	public ArrayVariable(NameProvider np, String name, PhysicalScope scope, Class<T> type, Supplier<Evaluable<? extends T>> p) {
		super(name, scope, type, p);
		this.names = np;
	}

	public NameProvider getNameProvider() { return names; }

	public void setArraySize(Expression<Integer> arraySize) { this.arraySize = arraySize; }

	@Override
	public Expression<Integer> getArraySize() {
		if (arraySize != null) return arraySize;
		return super.getArraySize();
	}

	@Override
	public int getKernelIndex() {
		if (getOriginalProducer() instanceof KernelIndex) {
			return ((KernelIndex) getOriginalProducer()).getKernelIndex();
		}

		return 0;
	}

	@Override
	public void setDelegate(ArrayVariable<T> delegate) {
		super.setDelegate(delegate);
	}

	public int getDelegateOffset() { return delegateOffset; }
	public void setDelegateOffset(int delegateOffset) { this.delegateOffset = delegateOffset; }

	public int getOffset() {
		if (getDelegate() == null) {
			return 0;
		} else {
			return getDelegate().getOffset() + getDelegateOffset();
		}
	}

	public Expression<Double> getValueRelative(int index) {
		TraversableExpression exp = TraversableExpression.traverse(getProducer());

		if (exp != null && !exp.isItem()) {
			Expression<Double> value = exp.getValueRelative(new IntegerConstant(index));
			if (value != null) return value;
		}

		if (getDelegate() != null) {
			Expression<Double> v = getDelegate().getValueRelative(index + getDelegateOffset());
			if (v instanceof InstanceReference) {
				((InstanceReference) v).getReferent().setOriginalProducer(getOriginalProducer());
			}
			return v;
		}

		return (Expression) getRaw(names.getArrayPosition(this, new IntegerConstant(index), getKernelIndex()));
	}

	public InstanceReference<T> getRelative(Expression<?> pos) {
		if (getDelegate() != null) {
			InstanceReference<T> v = getDelegate().getRelative(pos.add(getDelegateOffset()));
			((InstanceReference) v).getReferent().setOriginalProducer(getOriginalProducer());
			return v;
		} else if (getKernelIndex() < 0) {
			return getRaw(pos);
		} else {
			return getRaw(names.getArrayPosition(this, pos, getKernelIndex()));
		}
	}

	public InstanceReference<T> getAbsolute(Expression<?> pos) {
		return getRaw(pos);
	}

	protected InstanceReference<T> getRaw(Expression<?> pos) {
		if (getDelegate() == null) {
			pos = pos.add(getOffsetValue());
			return new InstanceReference(new Variable<>(dereference.apply(getName(), pos.toInt().getSimpleExpression()),
					false, new Expression(getType()), this), pos);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			InstanceReference ref = getDelegate().getRaw(pos.add(getDelegateOffset()));
			ref.getReferent().setOriginalProducer(getOriginalProducer());
			return ref;
		}
	}

	public Expression getOffsetValue() {
		return new StaticReference<>(Integer.class, getName() + "Offset");
	}

	public Expression getDimValue() {
		return new StaticReference<>(Integer.class, names.getVariableDimName(this, 0), this);
	}

	public Expression<Integer> length() {
		return new StaticReference<>(Integer.class, names.getVariableSizeName(this), this);
	}

	@Override
	public void setExpression(Expression<T> value) {
		if (getDelegate() != null)
			throw new RuntimeException("The expression should not be referenced directly, as this variable delegates to another variable");
		super.setExpression(value);
	}

	@Override
	public Expression<T> getExpression() {
		if (getDelegate() == null) return super.getExpression();
		throw new RuntimeException("The expression should not be referenced directly, as this variable delegates to another variable");
	}

	@Override
	protected List<Variable<?, ?>> getExpressionDependencies() {
		if (getDelegate() == null) return super.getExpressionDependencies();
		return Collections.emptyList();
	}
}
