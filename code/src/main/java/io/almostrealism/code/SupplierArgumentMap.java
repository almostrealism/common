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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class SupplierArgumentMap<S, A> implements ArgumentMap<Supplier, ArrayVariable<A>> {
	protected ArgumentProvider delegateProvider = DefaultScopeInputManager.getInstance();
	private final Map<Supplier<S>, ArrayVariable<A>> arguments;

	public SupplierArgumentMap() {
		this.arguments = new HashMap<>();
	}

	public void setDelegateProvider(ArgumentProvider provider) {
		this.delegateProvider = provider;
	}
	
	public void put(Supplier<S> key, ArrayVariable<A> value) {
		arguments.put(key, value);
	}

	@Override
	public void add(Supplier key) { }

	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		return arguments.get(key);
	}

	protected Optional<ArrayVariable<A>> get(Predicate<Supplier> filter, NameProvider p) {
		Optional<Supplier<S>> existing = arguments.keySet().stream().filter(filter).findAny();
		if (existing.isEmpty()) return Optional.empty();
		return Optional.of(get(existing.get(), p));
	}

	public ScopeInputManager getScopeInputManager() {
		return new ScopeInputManager() {
			@Override
			public <T> ArrayVariable<T> getArgument(NameProvider p, Supplier<Evaluable<? extends T>> input,
													ArrayVariable<T> delegate, int delegateOffset) {
				ArrayVariable arg = get(input, p);
				if (arg != null) return arg;

				arguments.put((Supplier) input, (ArrayVariable) delegateProvider.getArgument(p, input, delegate, delegateOffset));
				return (ArrayVariable) get(input, p);
			}
		};
	}
}
