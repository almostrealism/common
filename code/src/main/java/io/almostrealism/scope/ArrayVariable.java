/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.expression.Constant;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class ArrayVariable<T> extends Variable<T, ArrayVariable<T>> implements Array<T, ArrayVariable<T>> {
	private final NameProvider names;

	private int delegateOffset;
	private Expression<Integer> arraySize;
	private boolean disableOffset;
	private boolean destroyed;

	public ArrayVariable(NameProvider np, Class<T> type, String name, Expression<Integer> arraySize) {
		super(name, np == null ? null : np.getDefaultPhysicalScope(), null, null);
		this.names = np;
		setArraySize(arraySize);
		if (type != null) setExpression(new Constant<>(type));
	}

	public ArrayVariable(NameProvider np, String name, Supplier<Evaluable<? extends T>> producer) {
		this(np, name, np.getDefaultPhysicalScope(), (Class<T>) Double.class, producer);
	}

	public ArrayVariable(NameProvider np, String name, PhysicalScope scope, Class<T> type, Supplier<Evaluable<? extends T>> p) {
		super(name, scope, new Constant<>(type), p);
		this.names = np;
	}

	public void setArraySize(Expression<Integer> arraySize) { this.arraySize = arraySize; }

	@Override
	public Expression<Integer> getArraySize() {
		if (destroyed) throw new UnsupportedOperationException();
		if (arraySize != null) return arraySize;
		return super.getArraySize();
	}

	@Override
	public void setDelegate(ArrayVariable<T> delegate) {
		super.setDelegate(delegate);
	}

	public int getDelegateOffset() { return delegateOffset; }
	public void setDelegateOffset(int delegateOffset) { this.delegateOffset = delegateOffset; }

	public boolean isDisableOffset() {
		return disableOffset;
	}

	public void setDisableOffset(boolean disableOffset) {
		this.disableOffset = disableOffset;
	}

	public int getOffset() {
		if (destroyed) throw new UnsupportedOperationException();

		if (getDelegate() == null) {
			return 0;
		} else {
			return getDelegate().getOffset() + getDelegateOffset();
		}
	}

	public Expression<Double> getValueRelative(int index) {
		if (destroyed) throw new UnsupportedOperationException();

		TraversableExpression exp = TraversableExpression.traverse(getProducer());

		if (exp != null) {
			Expression<Double> value = exp.getValueRelative(new IntegerConstant(index));
			if (value != null) return value;
		}

		if (getDelegate() != null) {
			return getDelegate().getValueRelative(index + getDelegateOffset());
		}

		return (Expression) reference(getArrayPosition(this, new IntegerConstant(index), 0), false);
	}

	@Override
	public Expression<T> valueAt(Expression<?> exp) {
		if (destroyed) throw new UnsupportedOperationException();
		return referenceRelative(exp);
	}

	public Expression<T> ref(int pos) {
		if (destroyed) throw new UnsupportedOperationException();
		return referenceRelative(new IntegerConstant(pos));
	}

	public Expression<T> referenceRelative(Expression<?> pos) {
		if (getDelegate() != null) {
			return getDelegate().referenceRelative(pos.add(getDelegateOffset()));
		} else {
			return reference(getArrayPosition(this, pos, 0), false);
		}
	}

	public Expression<T> referenceAbsolute(Expression<?> pos) {
		return reference(pos, false);
	}

	public Expression<T> referenceDynamic(Expression<?> pos) {
		return reference(pos, true);
	}

	protected Expression<T> reference(Expression<?> pos, boolean dynamic) {
		if (destroyed) throw new UnsupportedOperationException();

		if (getDelegate() == null) {
			return InstanceReference.create(this, pos, dynamic);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			return getDelegate().reference(pos.add(getDelegateOffset()), false);
		}
	}

	public Expression getOffsetValue() {
		if (destroyed) throw new UnsupportedOperationException();

		return new StaticReference<>(Integer.class, getName() + "Offset");
	}

	public Expression getDimValue() {
		if (destroyed) throw new UnsupportedOperationException();

		return new StaticReference<>(Integer.class, names.getVariableDimName(this, 0), this);
	}

	public Expression<Integer> length() {
		if (destroyed) throw new UnsupportedOperationException();

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
		if (destroyed) throw new UnsupportedOperationException();

		if (getDelegate() == null) return super.getExpression();
		throw new RuntimeException("The expression should not be referenced directly, as this variable delegates to another variable");
	}

	@Override
	protected List<Variable<?, ?>> getExpressionDependencies() {
		if (destroyed) throw new UnsupportedOperationException();
		if (getDelegate() == null) return super.getExpressionDependencies();
		return Collections.emptyList();
	}

	private Expression<?> getArrayPosition(ArrayVariable v, Expression pos, int kernelIndex) {
		Expression offset = new IntegerConstant(0);

		if (v.getProducer() instanceof Countable) {
			KernelIndex idx = new KernelIndex(kernelIndex);
			Expression dim = new StaticReference(Integer.class, names.getVariableDimName(v, kernelIndex));

			Expression kernelOffset = idx.multiply(dim);
			return kernelOffset.add(offset).add(pos.toInt());
		} else {
			return offset.add(pos).toInt();
		}
	}

	public void destroy() {
		this.destroyed = true;
	}
}
