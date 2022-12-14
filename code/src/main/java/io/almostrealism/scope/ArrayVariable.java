/*
 * Copyright 2021 Michael Murray
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
import io.almostrealism.code.KernelIndex;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Evaluable;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class ArrayVariable<T> extends Variable<T, ArrayVariable<T>> implements Array<T, ArrayVariable<T>> {
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

	@Override
	public InstanceReference<T> get(String pos, Variable... dependencies) {
		if (getDelegate() == null) {
			return new InstanceReference(new Variable<>(names.getVariableValueName(this, pos), false, new Expression(getType()), this),
											dependencies);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			InstanceReference ref = getDelegate().get(pos + " + " + getDelegateOffset(), dependencies);
			ref.getReferent().setOriginalProducer(getOriginalProducer());
			return ref;
		}
	}

	public Expression<Integer> length() {
		return new Expression<Integer>(Integer.class, names.getVariableSizeName(this));
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
