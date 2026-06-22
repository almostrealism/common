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

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A caching {@link ScopeInputManager} that maps {@link Supplier} keys to {@link ArrayVariable}
 * values assigned during scope compilation.
 *
 * <p>{@code SupplierArgumentMap} is backed by a {@link HashMap} and is used during scope compilation
 * to track which input producers have already been assigned argument variables, preventing duplicate
 * allocations. New argument variables are created by delegating to another {@link ScopeInputManager}
 * (the {@link #getDelegateProvider() delegate provider}).</p>
 *
 * @param <S> the supplier element type
 * @param <A> the array element type for the variables
 *
 * @see ScopeInputManager
 */
public class SupplierArgumentMap<S, A> implements ScopeInputManager, Destroyable {
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
	 * Returns the argument variable for the given input, creating and caching it on first request.
	 *
	 * <p>If a variable is already cached for the input it is returned; otherwise a new variable is
	 * created via the {@link #getDelegateProvider() delegate provider} and cached.</p>
	 *
	 * @param <T> the type of value produced by the input
	 * @param input the input producer
	 * @param delegate the optional delegate variable for memory sharing
	 * @param delegateOffset the offset into the delegate variable
	 * @return the argument variable for the input
	 */
	@Override
	public <T> ArrayVariable<T> getArgument(Supplier<Evaluable<? extends T>> input,
											ArrayVariable<T> delegate, int delegateOffset) {
		ArrayVariable arg = get(input);
		if (arg != null) return arg;

		arguments.put((Supplier) input, (ArrayVariable) delegateProvider.getArgument(input, delegate, delegateOffset));
		return (ArrayVariable) get(input);
	}
}
