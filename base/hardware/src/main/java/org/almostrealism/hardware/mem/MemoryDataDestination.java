/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.MemoryData;

import java.util.function.IntFunction;

/**
 * Factory-based {@link Evaluable} for creating output memory destinations.
 *
 * <p>{@link MemoryDataDestination} provides a lightweight {@link Evaluable} implementation that
 * creates destination memory on-demand using a size-based factory. It's primarily used for
 * operations that need to allocate result memory dynamically.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * // Factory that creates Bytes instances
 * MemoryDataDestination<Bytes> dest = new MemoryDataDestination<>(
 *     size -> new Multiple<>(size, i -> new Bytes(100))
 * );
 *
 * // Create destination for single result
 * Bytes result = dest.evaluate();  // Creates 1 Bytes[100]
 *
 * // Create destination for multiple results
 * Multiple<Bytes> results = dest.createDestination(10);  // Creates 10 Bytes[100]
 * }</pre>
 *
 * <h2>Integration with Evaluable</h2>
 *
 * <p>Implements {@link Evaluable#into(Object)} for result redirection:</p>
 * <pre>{@code
 * MemoryDataDestination<Bytes> dest = new MemoryDataDestination<>(...);
 * Bytes existing = new Bytes(100);
 *
 * // Redirect output to existing memory
 * Evaluable<Bytes> redirected = dest.into(existing);
 * Bytes result = redirected.evaluate();  // Returns 'existing'
 * }</pre>
 *
 * <h2>Common Pattern: Operation Results</h2>
 *
 * <pre>{@code
 * // Operation that produces MemoryData results
 * public class MyOperation implements Evaluable<MemoryData> {
 *     private MemoryDataDestination<MemoryData> destination;
 *
 *     public MyOperation() {
 *         this.destination = new MemoryDataDestination<>(
 *             size -> new Multiple<>(size, i -> new Bytes(getResultSize()))
 *         );
 *     }
 *
 *     public MemoryData evaluate(Object... args) {
 *         MemoryData result = destination.evaluate();
 *         // Fill result with computation...
 *         return result;
 *     }
 * }
 * }</pre>
 *
 * @param <T> MemoryData type for destinations
 * @see Evaluable
 * @see Multiple
 */
// TODO  Should implement StreamingEvaluable
public class MemoryDataDestination<T extends MemoryData> implements Evaluable<T> {
	private final IntFunction<Multiple<T>> provider;

	public MemoryDataDestination(IntFunction<Multiple<T>> provider) {
		this.provider = provider;
	}

	@Override
	public Multiple<T> createDestination(int size) {
		return provider.apply(size);
	}

	@Override
	public Evaluable<T> into(Object destination) {
		return args -> (T) destination;
	}

	@Override
	public T evaluate(Object... args) {
		return createDestination(1).get(0);
	}
}
