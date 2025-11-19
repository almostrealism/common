/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.DataContext;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.lifecycle.SuppliedValue;
import org.almostrealism.hardware.Hardware;

import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Abstract base class for managing stack-based context-specific values with automatic lifecycle management.
 *
 * <p>{@link ContextSpecific} provides a pattern for values that vary by nested {@link DataContext}
 * instances, automatically creating and destroying values as contexts are created and destroyed. Values
 * are organized in a stack to support nested context scopes.</p>
 *
 * <h2>Stack-Based Lifecycle</h2>
 *
 * <p>When a new context starts, a new value is pushed onto the stack. When the context is destroyed,
 * the value is popped and optionally disposed:</p>
 * <pre>{@code
 * // Initial state: stack is empty
 * contextSpecific.init();  // Push default value
 *
 * // Context 1 starts
 * context1.start();  // Pushes new value (depth: 2)
 *   T value1 = contextSpecific.getValue();  // Returns top of stack
 *
 *   // Context 2 starts (nested)
 *   context2.start();  // Pushes new value (depth: 3)
 *     T value2 = contextSpecific.getValue();  // Returns different value
 *   context2.destroy();  // Pops and disposes value2 (depth: 2)
 *
 * context1.destroy();  // Pops and disposes value1 (depth: 1)
 * }</pre>
 *
 * <h2>Usage Pattern</h2>
 *
 * <p>Create a context-specific value by providing a supplier and optional disposal logic:</p>
 * <pre>{@code
 * // ThreadLocal implementation for thread-specific caches
 * ContextSpecific<Cache> cacheProvider = new ThreadLocalContextSpecific<>(
 *     () -> new Cache(1000),  // Create cache
 *     cache -> cache.clear()   // Clean up when context destroyed
 * );
 *
 * // Register with hardware context
 * cacheProvider.init();
 *
 * // Access the current context's cache
 * Cache cache = cacheProvider.getValue();
 * }</pre>
 *
 * <h2>Subclass Implementation</h2>
 *
 * <p>Subclasses must implement {@link #createValue(Supplier)} to control how values are created:</p>
 * <pre>{@code
 * // Default implementation: Creates new SuppliedValue
 * public class DefaultContextSpecific<T> extends ContextSpecific<T> {
 *     @Override
 *     public SuppliedValue<T> createValue(Supplier<T> supply) {
 *         return new SuppliedValue<>(supply);
 *     }
 * }
 *
 * // ThreadLocal implementation: Creates ThreadLocalSuppliedValue
 * public class ThreadLocalContextSpecific<T> extends ContextSpecific<T> {
 *     @Override
 *     public ThreadLocalSuppliedValue<T> createValue(Supplier<T> supply) {
 *         return new ThreadLocalSuppliedValue<>(supply);
 *     }
 * }
 * }</pre>
 *
 * <h2>Automatic Registration</h2>
 *
 * <p>Calling {@link #init()} registers this instance with {@link Hardware#addContextListener},
 * ensuring automatic value management across all context lifecycle events.</p>
 *
 * <h2>Stack Depth Warning</h2>
 *
 * <p>If the stack depth exceeds 3, a warning is logged. This typically indicates:</p>
 * <ul>
 *   <li>Deeply nested contexts (unusual but valid)</li>
 *   <li>Context leaks (contexts not being destroyed properly)</li>
 *   <li>Missing context cleanup in error paths</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This base class is NOT thread-safe. Use {@link ThreadLocalContextSpecific} for
 * thread-specific values or synchronize externally if shared across threads.</p>
 *
 * @param <T> Type of context-specific value
 * @see DefaultContextSpecific
 * @see ThreadLocalContextSpecific
 * @see ContextListener
 */
public abstract class ContextSpecific<T> implements ContextListener, Destroyable, ConsoleFeatures {
	private Stack<SuppliedValue<T>> val;
	private Supplier<T> supply;
	private Consumer<T> disposal;

	/**
	 * Constructs a context-specific value with the given supplier and no disposal logic.
	 *
	 * @param supply Supplier to create new instances for each context
	 */
	public ContextSpecific(Supplier<T> supply) {
		this(supply, null);
	}

	/**
	 * Constructs a context-specific value with the given supplier and disposal logic.
	 *
	 * @param supply Supplier to create new instances for each context
	 * @param disposal Optional consumer to clean up values when contexts are destroyed (may be null)
	 */
	public ContextSpecific(Supplier<T> supply, Consumer<T> disposal) {
		this.val = new Stack<>();
		this.supply = supply;
		this.disposal = disposal;
	}

	/**
	 * Initializes this context-specific value by creating an initial value and registering
	 * as a listener with the hardware context.
	 *
	 * <p>Call this method once after construction to enable automatic lifecycle management.</p>
	 */
	public void init() {
		if (val.isEmpty()) val.push(createValue(supply));
		Hardware.getLocalHardware().addContextListener(this);
	}

	/**
	 * Returns the value for the current context.
	 *
	 * <p>If no value exists yet (stack is empty), creates and pushes an initial value.
	 * Returns the value at the top of the stack, which corresponds to the most recently
	 * started context.</p>
	 *
	 * <p><b>Warning:</b> If stack depth exceeds 3, logs a warning indicating potential
	 * context leaks.</p>
	 *
	 * @return The value for the current context
	 */
	public T getValue() {
		if (val.isEmpty()) val.push(createValue(supply));

		T v = val.peek().getValue();

		if (val.size() > 3) {
			warn(val.size() + " context layers for " + v.getClass().getSimpleName());
		}

		return v;
	}

	/**
	 * Creates a new {@link SuppliedValue} for a context.
	 *
	 * <p>Subclasses override this to control value creation behavior:</p>
	 * <ul>
	 *   <li>{@link DefaultContextSpecific}: Creates standard {@link SuppliedValue}</li>
	 *   <li>{@link ThreadLocalContextSpecific}: Creates {@link org.almostrealism.lifecycle.ThreadLocalSuppliedValue}</li>
	 * </ul>
	 *
	 * @param supply Supplier to use for creating the value
	 * @return A new SuppliedValue instance
	 */
	public abstract SuppliedValue<T> createValue(Supplier<T> supply);

	/**
	 * Called when a context starts. Pushes a new value onto the stack.
	 *
	 * @param ctx The context that started
	 */
	@Override
	public void contextStarted(DataContext ctx) {
		val.push(createValue(supply));
	}

	/**
	 * Called when a context is destroyed. Pops the top value and applies disposal logic.
	 *
	 * <p>If the stack is already empty (shouldn't happen in normal operation), this is a no-op.</p>
	 *
	 * @param ctx The context being destroyed
	 */
	@Override
	public void contextDestroyed(DataContext ctx) {
		if (val.isEmpty()) return;

		val.pop().applyAll(disposal);
	}

	/**
	 * Destroys all values in the stack and unregisters this listener.
	 *
	 * <p>Pops all values from the stack, applying disposal logic to each, then removes
	 * this instance from the hardware context's listener list.</p>
	 */
	@Override
	public void destroy() {
		while (!val.isEmpty()) {
			val.pop().applyAll(disposal);
		}

		Hardware.getLocalHardware().removeContextListener(this);
	}

	@Override
	public Console console() { return Hardware.console; }
}
