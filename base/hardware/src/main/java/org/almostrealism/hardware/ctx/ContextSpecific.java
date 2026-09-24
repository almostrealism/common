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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.lifecycle.SuppliedValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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
 * <p>The bookkeeping of which value serves which context is synchronized, so the
 * instance may be shared across threads. The values themselves are shared too unless
 * {@link ThreadLocalContextSpecific} is used, which keeps one per thread.</p>
 *
 * @param <T> Type of context-specific value
 * @see DefaultContextSpecific
 * @see ThreadLocalContextSpecific
 * @see ContextListener
 */
public abstract class ContextSpecific<T> implements ContextListener, Destroyable, ConsoleFeatures {
	/** Values by the compute context they were created under, the current one on top. */
	private Deque<ContextValue<T>> val;

	/** Supplier used to create new values when contexts start. */
	private Supplier<T> supply;

	/** Optional consumer to clean up values when contexts are destroyed. */
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
		this.val = new ArrayDeque<>();
		this.supply = supply;
		this.disposal = disposal;
	}

	/**
	 * Initializes this context-specific value by creating an initial value and registering
	 * as a listener with the hardware context.
	 *
	 * <p>Call this method once after construction to enable automatic lifecycle management.</p>
	 */
	public synchronized void init() {
		if (val.isEmpty()) push();
		Hardware.getLocalHardware().addContextListener(this);
	}

	/**
	 * Returns the value for the current context.
	 *
	 * <p>A value belongs to the {@link ComputeContext} that was current when it was
	 * created: a compiled kernel dispatches through that context's command runner and
	 * memory provider, and a scoped data context, a temporary compute context, and a
	 * switch between live contexts all change which one that is. A value whose context
	 * has since been destroyed is disposed of first and never returned. When the value
	 * on top belongs to a different context that is still alive, the value already held
	 * for the current context is used, or a new one is created. Registration via
	 * {@link #init()} adds lifecycle callbacks on top of this; it is not required for
	 * correctness.</p>
	 *
	 * <p><b>Warning:</b> If more than 3 values are held, logs a warning indicating
	 * potential context leaks.</p>
	 *
	 * @return The value for the current context
	 */
	public synchronized T getValue() {
		discardOrphans();

		ComputeContext<?> current = currentContext();

		if (val.isEmpty()) {
			push();
		} else if (!val.peek().belongsTo(current)) {
			ContextValue<T> existing = valueFor(current);

			if (existing == null) {
				push();
			} else {
				val.remove(existing);
				val.push(existing);
			}
		}

		ContextValue<T> top = val.peek();
		boolean firstMaterialization = !top.isAvailable();
		T v = top.getValue();
		if (firstMaterialization) top.bindTo(current);

		if (val.size() > 3) {
			warn(val.size() + " context layers for " + v.getClass().getSimpleName());
		}

		return v;
	}

	/** Pushes a new value, recorded as belonging to the current compute context. */
	private void push() {
		val.push(new ContextValue<>(createValue(supply), currentContext()));
	}

	/**
	 * Returns the compute context a value created now belongs to, chosen under the
	 * requirements active on this thread as an operation compiled now would be, or
	 * {@code null} when there is none to ask, in which case the value serves every
	 * context.
	 */
	private ComputeContext<?> currentContext() {
		Hardware hardware = Hardware.getLocalHardware();
		if (hardware == null) return null;

		ComputeRequirement[] active = hardware.getComputer().getActiveRequirements()
				.toArray(ComputeRequirement[]::new);
		if (hardware.getDataContext(false, false, active) == null) return null;

		List<ComputeContext<MemoryData>> contexts = hardware.getComputeContexts(false, false, active);
		return contexts.isEmpty() ? null : contexts.get(0);
	}

	/** Returns the value held for the given context, or {@code null}. */
	private ContextValue<T> valueFor(ComputeContext<?> context) {
		for (ContextValue<T> v : val) {
			if (v.belongsTo(context)) return v;
		}

		return null;
	}

	/**
	 * Disposes of every value whose context has been destroyed, wherever it sits:
	 * switching between live contexts moves values around, so an orphan is not
	 * necessarily on top.
	 */
	private void discardOrphans() {
		for (ContextValue<T> v : List.copyOf(val)) {
			if (v.isOrphaned()) {
				val.remove(v);
				dispose(v);
			}
		}
	}

	/**
	 * Applies the disposal logic to a value that is no longer held. Disposal is best
	 * effort, since the resources the value refers to may already have gone with
	 * its context.
	 */
	private void dispose(ContextValue<T> value) {
		try {
			value.dispose(disposal);
		} catch (RuntimeException e) {
			warn("Unable to dispose of value from " + value.getContextName(), e);
		}
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
	 * Called when a context starts. Pushes a new value for it.
	 *
	 * @param ctx The context that started
	 */
	@Override
	public synchronized void contextStarted(DataContext ctx) {
		push();
	}

	/**
	 * Called when a context is destroyed. Disposes of every value created under that
	 * context, wherever it sits, since switches between live contexts may have moved
	 * it away from the top.
	 *
	 * @param ctx The context being destroyed
	 */
	@Override
	public synchronized void contextDestroyed(DataContext ctx) {
		if (ctx == null) {
			if (!val.isEmpty()) dispose(val.pop());
			return;
		}

		for (ContextValue<T> v : List.copyOf(val)) {
			if (v.createdUnder(ctx)) {
				val.remove(v);
				dispose(v);
			}
		}
	}

	/**
	 * Destroys all values held and unregisters this listener.
	 *
	 * <p>Applies the disposal logic to each value, then removes this instance from
	 * the hardware context's listener list.</p>
	 */
	@Override
	public synchronized void destroy() {
		while (!val.isEmpty()) {
			dispose(val.pop());
		}

		Hardware.getLocalHardware().removeContextListener(this);
	}

	/** Returns the console for logging context-specific events. */
	@Override
	public Console console() { return Hardware.console; }

	/**
	 * A value together with the {@link ComputeContext} it was created under, which
	 * decides whether it may still be handed out.
	 *
	 * @param <T> Type of context-specific value
	 */
	private static class ContextValue<T> {
		/** The value, created on first use. */
		private final SuppliedValue<T> value;

		/**
		 * The context current when this value was pushed, or null when there was none.
		 * Left mutable so a value pushed with no context available can bind to whichever
		 * context is current at the moment it is first materialized; see {@link #bindTo}.
		 */
		private ComputeContext<?> context;

		/**
		 * Records a value as belonging to a context.
		 *
		 * @param value   the value
		 * @param context the context current when the value was pushed, or null
		 */
		private ContextValue(SuppliedValue<T> value, ComputeContext<?> context) {
			this.value = value;
			this.context = context;
		}

		private T getValue() { return value.getValue(); }

		/** Returns whether this value has already been materialized (the supplier has run). */
		private boolean isAvailable() { return value.isAvailable(); }

		/**
		 * Binds this value to the given context if it was pushed with no context known
		 * (see {@link ContextSpecific#currentContext()}). Called right after the value is
		 * first materialized, so a value created lazily while no compute context was
		 * active is tagged with the context that was actually current when its content
		 * came into existence, instead of remaining a permanent wildcard that {@link #belongsTo}
		 * would keep matching against every context that comes along afterwards.
		 */
		private void bindTo(ComputeContext<?> current) {
			if (context == null && current != null) context = current;
		}

		/** Returns whether the context this value was created under has been destroyed. */
		private boolean isOrphaned() {
			return context != null && (context.isDestroyed() || context.getDataContext().isDestroyed());
		}

		/**
		 * Returns whether this value may serve the given context: the same one it was
		 * created under, or either unknown.
		 */
		private boolean belongsTo(ComputeContext<?> current) {
			return context == null || current == null || context == current;
		}

		// TODO(review): an unclaimed context==null entry never matches this, so contextDestroyed() can't dispose it; see review-followup memory.
		/** Returns whether this value was created under a compute context of the given data context. */
		private boolean createdUnder(DataContext<?> dataContext) {
			return context != null && context.getDataContext() == dataContext;
		}

		/** Names the context this value was created under, for reporting. */
		private String getContextName() {
			return context == null ? "unknown context" : context.getDataContext().getName();
		}

		/** Applies the disposal logic to the value if it was ever created. */
		private void dispose(Consumer<T> disposal) {
			value.applyAll(disposal);
		}
	}
}
