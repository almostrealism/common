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

package io.almostrealism.relation;

/**
 * A simple {@link FixedEvaluable} implementation that wraps a constant value.
 *
 * <p>{@link Provider} is the simplest form of {@link Evaluable} - it holds a
 * pre-computed value and returns it on every evaluation. This is useful for:</p>
 * <ul>
 *   <li>Wrapping literal values in the computation framework</li>
 *   <li>Providing constant inputs to computations</li>
 *   <li>Creating leaf nodes in computation graphs</li>
 * </ul>
 *
 * <h2>Behavior</h2>
 * <p>Unlike dynamic {@link Evaluable} implementations, a {@link Provider}:</p>
 * <ul>
 *   <li>Ignores all arguments passed to {@link #evaluate(Object...)}</li>
 *   <li>Always returns the same value (the one provided at construction)</li>
 *   <li>Has no computational overhead beyond returning the stored reference</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a provider for a constant value
 * Provider<Double> constant = new Provider<>(Math.PI);
 *
 * // Evaluation always returns the same value
 * double value1 = constant.evaluate();  // Returns Math.PI
 * double value2 = constant.evaluate("ignored", 123);  // Also returns Math.PI
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>A {@link Provider} is inherently thread-safe because its value is final
 * and immutable after construction. However, the contained value itself may
 * or may not be thread-safe.</p>
 *
 * @param <T> the type of value this provider holds
 *
 * @see FixedEvaluable
 * @see Evaluable
 *
 * @author Michael Murray
 */
public class Provider<T> implements FixedEvaluable<T> {
	private final T value;

	/**
	 * Creates a new {@link Provider} that holds the specified value.
	 *
	 * @param v the value to provide on evaluation
	 */
	public Provider(T v) {
		value = v;
	}

	/**
	 * Returns the value held by this provider.
	 *
	 * <p>This method always returns the same value that was provided
	 * at construction time.</p>
	 *
	 * @return the constant value
	 */
	@Override
	public T get() { return value; }
}
