/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.expression.DimValue;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.SizeValue;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Multiple;

import java.util.Objects;
import java.util.function.Supplier;

public class ArrayVariable<T> extends Variable<Multiple<T>, ArrayVariable<T>> implements Array<T, ArrayVariable<T>> {

	private Expression<Integer> delegateOffset;
	private Expression<Integer> arraySize;
	private boolean disableOffset;
	private boolean destroyed;

	public ArrayVariable(Class<T> type, String name, Expression<Integer> arraySize) {
		this(PhysicalScope.GLOBAL, type, name, arraySize, null);
	}

	public ArrayVariable(PhysicalScope scope,
						 Class<T> type, String name,
						 Expression<Integer> arraySize,
						 Supplier<Evaluable<? extends Multiple<T>>> p) {
		super(name, scope, type, p);
		setArraySize(arraySize);
	}

	public ArrayVariable(String name, Supplier<Evaluable<? extends Multiple<T>>> producer) {
		this(name, PhysicalScope.GLOBAL, Double.class, producer);
	}

	public ArrayVariable(String name, PhysicalScope scope, Class<?> type,
						 Supplier<Evaluable<? extends Multiple<T>>> p) {
		super(name, scope, type, p);
	}

	public ArrayVariable(ArrayVariable<T> delegate, Expression<Integer> delegateOffset) {
		super(null, delegate.getPhysicalScope(), null, null);
		setDelegate(delegate);
		setDelegateOffset(delegateOffset);
	}

	public void setArraySize(Expression<Integer> arraySize) { this.arraySize = arraySize; }

	public Expression<Integer> getArraySize() {
		if (destroyed) throw new UnsupportedOperationException();

		return arraySize;
	}

	@Override
	public void setDelegate(ArrayVariable<T> delegate) {
		super.setDelegate(delegate);
	}

	public Expression<Integer> getDelegateOffset() { return delegateOffset; }
	public void setDelegateOffset(Expression<Integer> delegateOffset) { this.delegateOffset = delegateOffset; }
	public void setDelegateOffset(int delegateOffset) { setDelegateOffset(new IntegerConstant(delegateOffset)); }

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
			return getDelegate().getOffset() + getDelegateOffset().intValue().getAsInt();
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
			return getDelegate().getValueRelative(index + getDelegateOffset().intValue().getAsInt());
		}

		return (Expression) referenceRelative(new IntegerConstant(index), new KernelIndex());
	}

	@Override
	public Expression<T> valueAt(Expression<?> exp) {
		if (destroyed) throw new UnsupportedOperationException();
		return referenceRelative(exp);
	}

	public InstanceReference<Multiple<T>, T> ref(int pos) {
		return ref(new IntegerConstant(pos));
	}

	public InstanceReference<Multiple<T>, T> ref(Expression<Integer> offset) {
		if (destroyed) throw new UnsupportedOperationException();
		return new InstanceReference<>(new ArrayVariable<>(this, offset));
	}

	@Deprecated
	public Expression<T> referenceRelative(int pos) {
		if (destroyed) throw new UnsupportedOperationException();
		return referenceRelative(new IntegerConstant(pos));
	}

	@Deprecated
	public Expression<T> referenceRelative(Expression<?> pos) {
		return referenceRelative(pos, new KernelIndex());
	}

	public Expression<T> referenceRelative(Expression<?> pos, KernelIndex idx) {
		if (getDelegate() != null) {
			return getDelegate().referenceRelative(pos.add(getDelegateOffset()));
		} else {
			return reference(getArrayPosition(pos, idx), false);
		}
	}

	public Expression<T> referenceAbsolute(Expression<?> pos) {
		return reference(pos, false);
	}

	@Deprecated
	public Expression<T> referenceDynamic(Expression<?> pos) {
		return reference(pos, true);
	}

	protected Expression<T> reference(Expression<?> pos, boolean dynamic) {
		if (destroyed) throw new UnsupportedOperationException();

		if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else if (getDelegate() != null) {
			return getDelegate().reference(pos.add(getDelegateOffset()), false);
		}

		Expression<?> index = pos;
		Expression<Boolean> condition = index.greaterThanOrEqual(new IntegerConstant(0));

		pos = index.toInt();
		if (dynamic) {
			index = pos.imod(length());
			pos = pos.divide(length()).multiply(getDimValue()).add(index);
		}

		InstanceReference<?, T> ref = new InstanceReference<>(this, pos, index);
		return ScopeSettings.enableInstanceReferenceMasking ? Mask.of(condition, ref) : ref;
	}

	public Expression getOffsetValue() {
		if (destroyed) throw new UnsupportedOperationException();

		return new StaticReference<>(Integer.class, getName() + "Offset");
	}

	public Expression getDimValue() {
		return getDimValue(0);
	}

	public Expression getDimValue(int dimension) {
		if (destroyed) throw new UnsupportedOperationException();

		if (ScopeSettings.enableDimensionMasking) {
			return new DimValue(this, dimension);
		} else if (dimension == 0) {
			return new SizeValue(this);
		} else {
			throw new IllegalArgumentException("Dimension masking is disabled");
		}
	}

	public Expression<Integer> length() {
		if (destroyed) throw new UnsupportedOperationException();

		return new SizeValue(this);
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ArrayVariable)) return false;
		if (!super.equals(obj)) return false;
		return Objects.equals(getArraySize(), ((ArrayVariable) obj).getArraySize());
	}

	private Expression<?> getArrayPosition(Expression pos, KernelIndex idx) {
		if (getProducer() instanceof Countable) {
			Expression dim = getDimValue(idx.getKernelAxis());

			Expression kernelOffset = idx.multiply(dim);
			return kernelOffset.add(pos.toInt());
		} else {
			return pos.toInt();
		}
	}

	public void destroy() {
		this.destroyed = true;
	}
}
