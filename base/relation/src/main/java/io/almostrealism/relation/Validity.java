/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.relation;

/**
 * An interface for types that can report their validity status.
 *
 * <p>{@link Validity} provides a standard way for objects to indicate whether
 * they are in a valid state. This is useful for validation, error checking,
 * and conditional processing based on object state.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Checking if computation results are meaningful</li>
 *   <li>Validating configuration objects</li>
 *   <li>Filtering invalid entries from collections</li>
 *   <li>Defensive programming with state validation</li>
 * </ul>
 *
 * <h2>Static Utility</h2>
 * <p>The {@link #valid(Object)} method provides a null-safe way to check
 * validity for any object. If the object implements {@link Validity}, its
 * {@link #isValid()} method is called; otherwise, non-null objects are
 * considered valid.</p>
 *
 * @author Michael Murray
 */
public interface Validity {
	/**
	 * Returns whether this object is in a valid state.
	 *
	 * @return {@code true} if valid, {@code false} otherwise
	 */
	boolean isValid();

	/**
	 * Checks the validity of any object.
	 *
	 * <p>If the object implements {@link Validity}, delegates to its
	 * {@link #isValid()} method. Otherwise, returns {@code true} for
	 * non-null objects and {@code false} for null.</p>
	 *
	 * @param <T> the type of the value
	 * @param value the value to check
	 * @return {@code true} if the value is valid
	 */
	static <T> boolean valid(T value) {
		if (value instanceof Validity) {
			return ((Validity) value).isValid();
		}

		return value != null;
	}
}
