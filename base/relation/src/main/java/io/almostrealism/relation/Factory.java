/*
 * Copyright 2016 Michael Murray
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
 * A generic factory interface for creating instances of type {@code V}.
 *
 * <p>This interface implements the Factory design pattern, providing a standardized
 * way to create objects without specifying their concrete classes. Unlike
 * {@link java.util.function.Supplier}, which uses {@code get()}, this interface
 * uses {@link #construct()} to emphasize the creation semantics.</p>
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>When object creation logic should be encapsulated</li>
 *   <li>When the exact type of object may vary at runtime</li>
 *   <li>When construction requires complex initialization</li>
 *   <li>When you want to decouple client code from concrete implementations</li>
 * </ul>
 *
 * <h2>Comparison with Related Types</h2>
 * <ul>
 *   <li>{@link java.util.function.Supplier} - Uses {@code get()}, general-purpose</li>
 *   <li>{@link Producer} - Creates {@link Evaluable}s for computation</li>
 *   <li>{@link Factory} - Uses {@code construct()}, emphasizes object creation</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Factory<Connection> connectionFactory = () -> new DatabaseConnection(config);
 * Connection conn = connectionFactory.construct();
 * }</pre>
 *
 * @param <V> the type of object this factory creates
 *
 * @see Producer
 * @see java.util.function.Supplier
 *
 * @author Michael Murray
 */
public interface Factory<V> {
	/**
	 * Constructs and returns a new instance of type {@code V}.
	 *
	 * <p>Each call to this method may return a new instance or a shared
	 * instance, depending on the implementation. Consult the specific
	 * factory implementation for its instance creation policy.</p>
	 *
	 * @return a newly constructed or retrieved instance
	 */
	V construct();
}
