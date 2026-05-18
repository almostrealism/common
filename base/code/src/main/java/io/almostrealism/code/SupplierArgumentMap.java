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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * An {@link ArgumentMap} that maps {@link Supplier} keys to {@link ArrayVariable} values.
 *
 * <p>{@code SupplierArgumentMap} is a general-purpose argument map backed by a {@link HashMap}.
 * It is used during scope compilation to track which input producers have already been assigned
 * argument variables, preventing duplicate allocations. A delegate {@link ScopeInputManager} is
 * used to create new argument variables for suppliers not yet in the map.</p>
 *
 * @param <S> the supplier element type
 * @param <A> the array element type for the variables
 *
 * @see ArgumentMap
 * @see ScopeInputManager
 */
public class SupplierArgumentMap<S, A> implements ArgumentMap<Supplier, ArrayVariable<A>> {
	/** The delegate scope input manager used to create new argument variables. */
	protected ScopeInputManager delegateProvider;
	/** The internal mapping from suppliers to their corresponding argument variables. */
	private final Map<Supplier<S>, ArrayVariable<A>> arguments;

	/**
	 * Creates a new empty supplier argument map.
	 */
	public SupplierArgumentMap() {
		this.arguments = new HashMap<>();
	}

	/**
	 * Sets the delegate scope input manager used for creating new argument variables.
	 *
	 * @param provider the delegate provider to use
	 */
	public void setDelegateProvider(ScopeInputManager provider) {
		this.delegateProvider = provider;
	}

	/**
	 * Returns the delegate scope input manager, initializing a default one if needed.
	 *
	 * @return the delegate scope input manager
	 */
	public ScopeInputManager getDelegateProvider() {
		if (delegateProvider == null) {
			delegateProvider = DefaultScopeInputManager.getInstance(null);
		}

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
	 * {@inheritDoc}
	 *
	 * <p>This implementation is a no-op; suppliers are added via {@link #put}.
	 *
	 * @param key the supplier to add (ignored)
	 */
	@Override
	public void add(Supplier key) { }

	/**
	 * {@inheritDoc}
	 *
	 * @param key the supplier key to look up
	 * @param p the name provider (unused in this implementation)
	 * @return the array variable for the given key, or {@code null} if not found
	 */
	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		return arguments.get(key);
	}

	/**
	 * Returns the first array variable whose key satisfies the given predicate.
	 *
	 * @param filter the predicate to filter supplier keys
	 * @param p the name provider (unused in this implementation)
	 * @return an optional containing the matched variable, or empty if none found
	 */
	protected Optional<ArrayVariable<A>> get(Predicate<Supplier> filter, NameProvider p) {
		Optional<Supplier<S>> existing = arguments.keySet().stream().filter(filter).findAny();
		if (existing.isEmpty()) return Optional.empty();
		return Optional.of(get(existing.get(), p));
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
			public <T> ArrayVariable<T> getArgument(NameProvider p, Supplier<Evaluable<? extends T>> input,
													ArrayVariable<T> delegate, int delegateOffset) {
				ArrayVariable arg = get(input, p);
				if (arg != null) return arg;

				arguments.put((Supplier) input, (ArrayVariable) getDelegateProvider().getArgument(p, input, delegate, delegateOffset));
				return (ArrayVariable) get(input, p);
			}
		};
	}
}
