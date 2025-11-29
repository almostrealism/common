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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.OperationList;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A data-receiving component in the Almost Realism computation graph architecture.
 * Receptors are the input endpoints that accept data "proteins" (wrapped as {@link Producer}
 * instances) pushed through the computation graph.
 *
 * <p>The Receptor interface is one half of the core cell communication pattern, with
 * {@link Transmitter} being the other half. Together, they enable a push-based data flow
 * model where data propagates through connected components.</p>
 *
 * <h2>Push-Based Data Flow</h2>
 * <p>When data is pushed to a receptor:</p>
 * <ol>
 *   <li>The receptor receives a {@link Producer} representing the incoming data</li>
 *   <li>It creates operations to process or forward that data</li>
 *   <li>It returns a {@link Supplier} of {@link Runnable} representing those operations</li>
 * </ol>
 *
 * <p>The operations are not executed immediately; instead, they are composed into
 * operation graphs that can be optimized and executed later.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a receptor that stores values into a collection
 * Receptor<PackedCollection> storage = input -> {
 *     return assign(destination, input);
 * };
 *
 * // Create a multi-target receptor
 * Receptor<PackedCollection> broadcast = Receptor.to(receptor1, receptor2, receptor3);
 * }</pre>
 *
 * @param <T> the type of data received, typically {@link org.almostrealism.collect.PackedCollection}
 * @see Transmitter
 * @see Cell
 * @author Michael Murray
 */
public interface Receptor<T> {
	/**
	 * Receives data pushed through the computation graph.
	 *
	 * <p>This method is called when upstream components want to send data to this receptor.
	 * The method should return operations that will process the incoming data when executed.</p>
	 *
	 * <p>Note: The returned operations are not executed immediately. They are collected
	 * and composed into an operation graph that can be optimized before execution.</p>
	 *
	 * @param protein the data producer being pushed to this receptor
	 * @return a supplier of runnable operations to process the incoming data
	 */
	Supplier<Runnable> push(Producer<T> protein);

	/**
	 * Creates a receptor that broadcasts pushed data to multiple downstream receptors.
	 * When data is pushed to the returned receptor, it will be forwarded to all
	 * provided downstream receptors in parallel.
	 *
	 * @param <T> the data type
	 * @param downstream the receptors to receive the broadcasted data
	 * @return a receptor that distributes data to all downstream receptors
	 * @throws IllegalArgumentException if any downstream receptor is null
	 */
	static <T> Receptor<T> to(Receptor<T>... downstream) {
		if (Stream.of(downstream).anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException();
		}

		return to(Stream.of(downstream));
	}

	/**
	 * Creates a receptor that broadcasts pushed data to multiple downstream receptors
	 * provided as a stream.
	 *
	 * @param <T> the data type
	 * @param downstream a stream of receptors to receive the broadcasted data
	 * @return a receptor that distributes data to all downstream receptors
	 */
	static <T> Receptor<T> to(Stream<Receptor<T>> downstream) {
		return protein -> downstream.map(r -> r.push(protein)).collect(OperationList.collector());
	}
}
