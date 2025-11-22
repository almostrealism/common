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

package io.almostrealism.relation;

import java.util.function.Function;

/**
 * A {@link Producer} implementation that wraps a simple function for dynamic evaluation.
 *
 * <p>{@link DynamicProducer} bridges standard Java functions with the Producer/Evaluable
 * framework. It allows any {@link Function} that takes arguments and produces a result
 * to be used as a {@link Producer}.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Wrapping existing functions in the computation framework</li>
 *   <li>Creating simple producers without custom classes</li>
 *   <li>Testing and prototyping computations</li>
 *   <li>Integrating external computation logic</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a dynamic producer from a lambda
 * DynamicProducer<Double> square = new DynamicProducer<>(args -> {
 *     double x = (Double) args[0];
 *     return x * x;
 * });
 *
 * // Use like any other producer
 * double result = square.evaluate(5.0);  // Returns 25.0
 * }</pre>
 *
 * <h2>Null Function Handling</h2>
 * <p>If constructed with a {@code null} function, the producer will throw
 * {@link UnsupportedOperationException} when evaluated.</p>
 *
 * @param <T> the type of the computation result
 *
 * @see Producer
 * @see Evaluable
 * @see Function
 *
 * @author Michael Murray
 */
public class DynamicProducer<T> implements Producer<T> {
	private final Function<Object[], T> function;

	/**
	 * Creates a new {@link DynamicProducer} that wraps the given function.
	 *
	 * @param function the function to wrap, or {@code null} for an unsupported producer
	 */
	public DynamicProducer(Function<Object[], T> function) {
		this.function = function;
	}

	/**
	 * Returns an {@link Evaluable} that applies the wrapped function.
	 *
	 * @return an evaluable that delegates to the wrapped function
	 */
	@Override
	public Evaluable<T> get() { return getFunction()::apply; }

	/**
	 * Returns the wrapped function, or a throwing function if none was provided.
	 *
	 * @return the function to use for evaluation
	 */
	protected Function<Object[], T> getFunction() {
		if (function == null) {
			return args -> {
				throw new UnsupportedOperationException();
			};
		}

		return function;
	}
}
