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

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Default implementation of {@link ScopeInputManager} that creates argument variables using a factory function.
 *
 * <p>The default factory names variables using a global counter and the name from the {@link NameProvider}.
 * A custom factory can be supplied to override naming or variable construction behavior.</p>
 *
 * @see ScopeInputManager
 * @see ArgumentProvider
 */
public class DefaultScopeInputManager implements ScopeInputManager {
	/** Global counter used to generate unique argument variable names. */
	protected static int counter = 0;

	/** The language operations for the compilation target. */
	private LanguageOperations lang;
	/** Factory function that creates array variables for input producers. */
	private BiFunction<NameProvider, Supplier<Evaluable<?>>, ArrayVariable<?>> variableFactory;

	/**
	 * Creates a new scope input manager with the default variable factory that uses a global counter.
	 *
	 * @param lang the language operations for the compilation target
	 */
	protected DefaultScopeInputManager(LanguageOperations lang) {
		variableFactory = (p, input) -> new ArrayVariable(p.getArgumentName(counter++), input);
		this.lang = lang;
	}

	/**
	 * Creates a new scope input manager with a custom variable factory.
	 *
	 * @param lang the language operations for the compilation target
	 * @param variableFactory the factory that creates argument variables
	 */
	public DefaultScopeInputManager(LanguageOperations lang,
									BiFunction<NameProvider, Supplier<Evaluable<?>>, ArrayVariable<?>> variableFactory) {
		this.lang = lang;
		this.variableFactory = variableFactory;
	}

	/**
	 * Sets the variable factory used to create argument variables.
	 *
	 * @param variableFactory the factory to use
	 */
	protected void setVariableFactory(BiFunction<NameProvider, Supplier<Evaluable<?>>, ArrayVariable<?>> variableFactory) {
		this.variableFactory = variableFactory;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the language operations
	 */
	@Override
	public LanguageOperations getLanguage() { return lang; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Creates a new argument variable using the factory, then applies any delegate and offset.
	 *
	 * @param <T> the type of value produced by the input
	 * @param p the name provider for generating variable names
	 * @param input the input producer
	 * @param delegate the optional delegate variable for memory sharing
	 * @param delegateOffset the offset into the delegate variable
	 * @return the array variable for the input
	 */
	@Override
	public <T> ArrayVariable<T> getArgument(NameProvider p, Supplier<Evaluable<? extends T>> input,
											ArrayVariable<T> delegate, int delegateOffset) {
		ArrayVariable arg = variableFactory.apply(p, (Supplier) input);
		arg.setDelegate(delegate);
		arg.setDelegateOffset(delegateOffset);
		return arg;
	}

	/**
	 * Returns a new {@link DefaultScopeInputManager} for the given language operations.
	 *
	 * @param lang the language operations (must not be {@code null})
	 * @return a new default scope input manager
	 * @throws UnsupportedOperationException if {@code lang} is {@code null}
	 */
	public static DefaultScopeInputManager getInstance(LanguageOperations lang) {
		if (lang == null) {
			throw new UnsupportedOperationException("LanguageOperations must be provided");
		}

		return new DefaultScopeInputManager(lang);
	}
}
