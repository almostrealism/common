/*
 * Copyright 2021 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;

import java.util.function.BiFunction;
import java.util.function.Supplier;

public class DefaultScopeInputManager implements ScopeInputManager {
	private static final DefaultScopeInputManager instance = new DefaultScopeInputManager();

	protected int counter;
	private BiFunction<NameProvider, Supplier<Evaluable<?>>, ArrayVariable<?>> variableFactory;

	public DefaultScopeInputManager() {
		variableFactory = (p, input) -> new ArrayVariable(p, p.getArgumentName(counter++), input);
	}

	public DefaultScopeInputManager(BiFunction<NameProvider, Supplier<Evaluable<?>>, ArrayVariable<?>> variableFactory) {
		this.variableFactory = variableFactory;
	}

	protected void setVariableFactory(BiFunction<NameProvider, Supplier<Evaluable<?>>, ArrayVariable<?>> variableFactory) {
		this.variableFactory = variableFactory;
	}

	@Override
	public <T> ArrayVariable<T> getArgument(NameProvider p, Supplier<Evaluable<? extends T>> input,
											ArrayVariable<T> delegate, int delegateOffset) {
		ArrayVariable arg = variableFactory.apply(p, (Supplier) input);
		arg.setDelegate(delegate);
		arg.setDelegateOffset(delegateOffset);

		if (delegate == null) {
			arg.setPhysicalScope(p.getDefaultPhysicalScope());
			arg.getExpression().setType(Double.class);
		}

		return arg;
	}

	public static DefaultScopeInputManager getInstance() { return instance; }
}
