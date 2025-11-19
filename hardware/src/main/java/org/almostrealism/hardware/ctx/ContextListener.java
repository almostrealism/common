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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.DataContext;

/**
 * Lifecycle hook interface for receiving notifications about {@link DataContext} lifecycle events.
 *
 * <p>{@link ContextListener} enables components to respond to context creation and destruction,
 * supporting automatic resource management and cleanup patterns throughout the hardware acceleration
 * system.</p>
 *
 * <h2>Usage Pattern</h2>
 *
 * <p>Typical usage involves registering listeners with the context to perform initialization
 * and cleanup tasks:</p>
 * <pre>{@code
 * public class ResourceManager implements ContextListener {
 *     private ThreadPool pool;
 *
 *     @Override
 *     public void contextStarted(DataContext ctx) {
 *         // Initialize resources when context starts
 *         pool = new ThreadPool(ctx.getName() + "-pool");
 *     }
 *
 *     @Override
 *     public void contextDestroyed(DataContext ctx) {
 *         // Clean up resources when context is destroyed
 *         if (pool != null) {
 *             pool.shutdown();
 *             pool = null;
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Integration with ContextSpecific</h2>
 *
 * <p>{@link ContextSpecific} implements this interface to manage stack-based lifecycle
 * of context-specific values:</p>
 * <pre>{@code
 * // ContextSpecific pushes new value when context starts
 * contextSpecific.contextStarted(ctx);  // Creates and pushes new value
 *
 * // ... use context-specific value ...
 *
 * // ContextSpecific pops value when context destroyed
 * contextSpecific.contextDestroyed(ctx);  // Pops and destroys value
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 *
 * <ul>
 *   <li><b>Thread Pool Management</b>: Create executor threads when context starts,
 *       shut down when destroyed</li>
 *   <li><b>Memory Provider Setup</b>: Initialize memory providers for specific contexts,
 *       clean up allocations on destruction</li>
 *   <li><b>Cache Management</b>: Create context-specific caches, invalidate on destruction</li>
 *   <li><b>Profiling Integration</b>: Start timing when context begins, record totals on
 *       destruction</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be thread-safe if the same listener is registered with multiple
 * contexts or if contexts can be created/destroyed from multiple threads.</p>
 *
 * @see ContextSpecific
 * @see HardwareDataContext
 * @see AbstractComputeContext
 */
public interface ContextListener {
	/**
	 * Called when a {@link DataContext} has been started and is ready for use.
	 *
	 * <p>Implementations should perform initialization tasks such as:
	 * <ul>
	 *   <li>Creating context-specific resources (thread pools, caches, etc.)</li>
	 *   <li>Registering with external services</li>
	 *   <li>Starting profiling or monitoring</li>
	 * </ul>
	 *
	 * @param ctx The context that was started
	 */
	void contextStarted(DataContext ctx);

	/**
	 * Called when a {@link DataContext} is being destroyed and should clean up resources.
	 *
	 * <p>Implementations should perform cleanup tasks such as:
	 * <ul>
	 *   <li>Shutting down thread pools</li>
	 *   <li>Releasing memory allocations</li>
	 *   <li>Closing open resources</li>
	 *   <li>Unregistering from external services</li>
	 * </ul>
	 *
	 * <p><b>IMPORTANT:</b> This method must be safe to call multiple times and should
	 * handle partial initialization gracefully (in case contextStarted threw an exception).
	 *
	 * @param ctx The context being destroyed
	 */
	void contextDestroyed(DataContext ctx);
}
