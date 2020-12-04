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

import org.almostrealism.relation.Evaluable;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.ScopeInputManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class SupplierArgumentMap<S, A> {
	private Map<Supplier<S>, Argument<A>> arguments;

	public SupplierArgumentMap() {
		this.arguments = new HashMap<>();
	}
	
	public void put(Supplier<S> key, Argument<A> value) {
		arguments.put(key, value);
	}

	public Argument<A> get(Supplier key) {
		return arguments.get(key);
	}

	protected Optional<Argument<A>> get(Predicate<Supplier> filter) {
		Optional<Supplier<S>> existing = arguments.keySet().stream().filter(filter).findAny();
		if (existing.isEmpty()) return Optional.empty();
		return Optional.of(get(existing.get()));
	}

	public ScopeInputManager getScopeInputManager() {
		return new ScopeInputManager() {
			@Override
			public <T> Argument<T> getArgument(NameProvider p, Supplier<Evaluable<? extends T>> input) {
				Argument arg = get(input);
				if (arg != null) return arg;

				arguments.put((Supplier) input, (Argument) DefaultScopeInputManager.getInstance().getArgument(p, input));
				return (Argument) get(input);
			}
		};
	}
}
