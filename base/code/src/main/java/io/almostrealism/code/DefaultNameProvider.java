/*
 * Copyright 2025 Michael Murray
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

/**
 * A simple {@link NameProvider} implementation backed by either a fixed function name
 * or a delegating {@link NamedFunction}.
 *
 * <p>When constructed with a {@link NamedFunction}, the function name is delegated to it
 * dynamically, allowing the name to change if the underlying function is renamed. When
 * constructed with a fixed string, the name is static.</p>
 *
 * @see NameProvider
 * @see NamedFunction
 */
public class DefaultNameProvider implements NameProvider {
	/** The fixed function name, or {@code null} if delegating to a named function. */
	private String function;
	/** The named function to delegate to, or {@code null} if using a fixed name. */
	private NamedFunction named;

	/**
	 * Creates a name provider with a fixed function name.
	 *
	 * @param function the function name to use
	 */
	public DefaultNameProvider(String function) {
		this.function = function;
	}

	/**
	 * Creates a name provider that delegates to a {@link NamedFunction}.
	 *
	 * @param named the named function to delegate to
	 */
	public DefaultNameProvider(NamedFunction named) {
		this.named = named;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the name from the delegated {@link NamedFunction} if one was provided,
	 * otherwise returns the fixed function name.
	 *
	 * @return the function name
	 */
	@Override
	public String getFunctionName() {
		return named == null ? function : named.getFunctionName();
	}
}
