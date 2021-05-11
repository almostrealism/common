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

package io.almostrealism.code;

import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.InstanceReference;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;

import java.util.Optional;
import java.util.function.Supplier;

public class ArrayVariable<T> extends Variable implements Array<T>, Delegated<ArrayVariable<T>> {
	private final NameProvider names;

	private ArrayVariable<T> delegate;
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
	public ArrayVariable<T> getDelegate() { return delegate; }
	public void setDelegate(ArrayVariable<T> delegate) { this.delegate = delegate; }

	public int getDelegateOffset() { return delegateOffset; }
	public void setDelegateOffset(int delegateOffset) { this.delegateOffset = delegateOffset; }

	@Override
	public InstanceReference<T> get(String pos, Variable... dependencies) {
		if (getDelegate() == null) {
			return new InstanceReference(new Variable<T>(names.getVariableValueName(this, pos), false, new Expression(getType()), this), dependencies);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			return getDelegate().get(pos + " + " + getDelegateOffset());
		}
	}
}
