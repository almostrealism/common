/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Maps {@link Supplier} keys to {@link ArrayVariable} values, caching the argument variables
 * assigned during scope compilation.
 *
 * <p>{@code SupplierArgumentMap} is backed by a {@link HashMap} and is used during scope compilation
 * to track which input producers have already been assigned argument variables, preventing duplicate
 * allocations. A delegate {@link ScopeInputManager} is used to create new argument variables for
 * suppliers not yet in the map.</p>
 *
 * @param <S> the supplier element type
 * @param <A> the array element type for the variables
 *
 * @see ScopeInputManager
 */
public class SupplierArgumentMap<S, A> implements Destroyable {
	/** The delegate scope input manager used to create new argument variables. */
	protected final ScopeInputManager delegateProvider;
	/** The internal mapping from suppliers to their corresponding argument variables. */
	private final Map<Supplier<S>, ArrayVariable<A>> arguments;

	/**
	 * Creates a new supplier argument map that delegates new variable creation to the given provider.
	 *
	 * @param delegateProvider the scope input manager used to create new argument variables
	 */
	public SupplierArgumentMap(ScopeInputManager delegateProvider) {
		this.delegateProvider = delegateProvider;
		this.arguments = new HashMap<>();
	}

	/**
	 * Returns the delegate scope input manager used to create new argument variables.
	 *
	 * @return the delegate scope input manager
	 */
	public ScopeInputManager getDelegateProvider() {
		return delegateProvider;
	}

	/**
	 * Associates the given supplier key with the given array variable.
	 *
	 * @param key the supplier key
	 * @param value the array variable to associate
	 */
	public void put(Supplier<S> key, ArrayVariable<A> value) {
		arguments.put(key, value);
	}

	/**
	 * Returns the argument variable for the given key.
	 *
	 * <p>If the key is a {@link Computation} that exposes an output variable via
	 * {@link Computation#getOutputVariable()}, that variable is returned directly, reusing the
	 * producer's output instead of allocating a new argument. Otherwise the cached variable for the
	 * key is returned, or {@code null} if none has been registered.</p>
	 *
	 * @param key the supplier key to look up
	 * @return the array variable for the given key, or {@code null} if not found
	 */
	public ArrayVariable<A> get(Supplier key) {
		if (key instanceof Computation) {
			ArrayVariable<A> out = (ArrayVariable<A>) ((Computation) key).getOutputVariable();
			if (out != null) return out;
		}

		return arguments.get(key);
	}

	/**
	 * Returns the first array variable whose key satisfies the given predicate.
	 *
	 * @param filter the predicate to filter supplier keys
	 * @return an optional containing the matched variable, or empty if none found
	 */
	protected Optional<ArrayVariable<A>> get(Predicate<Supplier> filter) {
		Optional<Supplier<S>> existing = arguments.keySet().stream().filter(filter).findAny();
		if (existing.isEmpty()) return Optional.empty();
		return Optional.of(get(existing.get()));
	}

	/**
	 * Returns a {@link ScopeInputManager} that uses this map's entries as a cache
	 * and delegates to the underlying provider for cache misses.
	 *
	 * @return a scope input manager backed by this argument map
	 */
	public ScopeInputManager getScopeInputManager() {
		return new ScopeInputManager() {
			@Override
			public LanguageOperations getLanguage() {
				return getDelegateProvider().getLanguage();
			}

			@Override
			public <T> ArrayVariable<T> getArgument(Supplier<Evaluable<? extends T>> input,
													ArrayVariable<T> delegate, int delegateOffset) {
				ArrayVariable arg = get(input);
				if (arg != null) return arg;

				arguments.put((Supplier) input, (ArrayVariable) getDelegateProvider().getArgument(input, delegate, delegateOffset));
				return (ArrayVariable) get(input);
			}
		};
	}
}
