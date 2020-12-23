/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.expressions.Expression;
import io.almostrealism.relation.Compactable;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class ComputationOperationAdapter<I, O> extends OperationAdapter<I> implements Computation<O>, Compactable {

	public ComputationOperationAdapter() {
		super(new Supplier[0]);
	}

	public void prepareScope(ScopeInputManager manager) {
		if (getArguments() != null) return;

		setArguments(getInputs().stream()
				.map(manager.argumentForInput(this)).collect(Collectors.toList()));

		getInputs().stream()
				.map(in -> in instanceof Computation ? (Computation) in : null)
				.filter(Objects::nonNull)
				.forEach(c -> c.prepareScope(manager));
	}

	@Override
	public ArrayVariable getArgument(int index) { return getArguments().get(index); }

	public Expression<Double> getInputValue(int index, int pos) {
		return getExpression(getArguments().get(index), pos);
	}

	@Override
	public Scope<O> getScope(NameProvider provider) {
		Scope<O> scope = new Scope<>(provider.getFunctionName());
		scope.getVariables().addAll(getVariables());
		return scope;
	}

	@Override
	public void compact() {
		super.compact();
		prepareScope(DefaultScopeInputManager.getInstance());
	}

	public static Expression<Double> getExpression(ArrayVariable arg, int pos) {
		return ((MultiExpression) arg.getProducer()).getValue(pos);
	}
}
