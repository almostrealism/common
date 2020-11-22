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

import org.almostrealism.relation.Computation;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.Compactable;

import java.util.function.Supplier;

public abstract class ComputationOperationAdapter<I, O> extends OperationAdapter<I> implements Computation<O>, Compactable {

	public ComputationOperationAdapter() {
		super(new Supplier[0]);
	}

	@Override
	public Argument getArgument(int index) { return getArguments().get(index); }

	@Override
	public Scope<O> getScope(NameProvider provider) {
		Scope<O> scope = new Scope<>(provider.getFunctionName());
		scope.getVariables().addAll(getVariables());
		return scope;
	}

	@Override
	public void compact() {
		super.compact();
		compileArguments();
	}

	/**
	 * This is not ideal, but for now it is used to make sure that
	 * {@link Scope} generation has occurred for all {@link Argument}s
	 * to this operation. It is required because that process is
	 * what properly sets up the {@link Argument}s of those
	 * {@link OperationAdapter}s that this operation depends on.
	 * Part of why it is not ideal is that if it is executed before
	 * {@link #compact()} is executed on the {@link OperationAdapter}s,
	 * their arguments may not be valid after executing {@link #compact()}.
	 */
	public void compileArguments() {
		for (Argument arg : getArguments()) {
			if (arg.getProducer() instanceof OperationAdapter) {
				((OperationAdapter) arg.getProducer()).compile();
			}
		}
	}
}
