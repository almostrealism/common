/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.lifecycle;

/**
 * A lifecycle interface for objects that can be reset to their initial state.
 *
 * <p>This interface provides a standardized way to reset stateful objects back to
 * a clean or initial state without requiring full destruction and reconstruction.
 * It is commonly used for objects that accumulate state over time and need periodic
 * reinitialization.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code Lifecycle} is designed for objects that need to:</p>
 * <ul>
 *   <li><strong>Clear Accumulated State:</strong> Reset counters, clear caches, flush buffers</li>
 *   <li><strong>Prepare for Reuse:</strong> Reinitialize for the next execution cycle</li>
 *   <li><strong>Release Temporary Resources:</strong> Clear transient data without full cleanup</li>
 *   <li><strong>Return to Initial Configuration:</strong> Restore default settings or values</li>
 * </ul>
 *
 * <h2>Comparison with Destroyable</h2>
 * <table>
 *   <tr>
 *     <th>Lifecycle.reset()</th>
 *     <th>Destroyable.destroy()</th>
 *   </tr>
 *   <tr>
 *     <td>Reinitializes for reuse</td>
 *     <td>Final cleanup before disposal</td>
 *   </tr>
 *   <tr>
 *     <td>Object remains usable</td>
 *     <td>Object should not be used after</td>
 *   </tr>
 *   <tr>
 *     <td>Can be called multiple times</td>
 *     <td>Typically called once at end of life</td>
 *   </tr>
 *   <tr>
 *     <td>Clears transient state</td>
 *     <td>Releases external resources</td>
 *   </tr>
 * </table>
 *
 * <h2>Default Implementation</h2>
 * <p>The default {@link #reset()} implementation is a no-op, allowing implementations
 * to only override it when reset behavior is needed. This follows the pattern of
 * optional lifecycle management.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Resetting a computational graph:</strong></p>
 * <pre>{@code
 * public class ComputationGraph implements Lifecycle {
 *     private Map<String, Object> cache = new HashMap<>();
 *     private List<Result> intermediateResults = new ArrayList<>();
 *
 *     @Override
 *     public void reset() {
 *         cache.clear();
 *         intermediateResults.clear();
 *         // Graph structure remains intact
 *     }
 *
 *     public void compute() {
 *         reset();  // Clear previous results
 *         // Perform new computation
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Resetting a counter:</strong></p>
 * <pre>{@code
 * public class ExecutionCounter implements Lifecycle {
 *     private long count = 0;
 *     private long totalTime = 0;
 *
 *     public void recordExecution(long duration) {
 *         count++;
 *         totalTime += duration;
 *     }
 *
 *     @Override
 *     public void reset() {
 *         count = 0;
 *         totalTime = 0;
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Pooled object reset:</strong></p>
 * <pre>{@code
 * public class PooledBuffer implements Lifecycle {
 *     private ByteBuffer buffer;
 *     private boolean inUse = false;
 *
 *     @Override
 *     public void reset() {
 *         buffer.clear();  // Reset position and limit
 *         inUse = false;
 *         // Buffer memory is retained for reuse
 *     }
 *
 *     public void returnToPool() {
 *         reset();
 *         pool.release(this);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Combining with Destroyable:</strong></p>
 * <pre>{@code
 * public class ManagedResource implements Lifecycle, Destroyable {
 *     private NativeBuffer nativeBuffer;
 *     private List<Task> pendingTasks = new ArrayList<>();
 *
 *     @Override
 *     public void reset() {
 *         // Clear transient state
 *         pendingTasks.clear();
 *         // Native buffer remains allocated
 *     }
 *
 *     @Override
 *     public void destroy() {
 *         // Final cleanup
 *         reset();  // Clear state first
 *         if (nativeBuffer != null) {
 *             nativeBuffer.free();  // Release native memory
 *             nativeBuffer = null;
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see Destroyable
 */
public interface Lifecycle {
	/**
	 * Resets this object to its initial state.
	 *
	 * <p>Implementations should clear any accumulated state, reset counters, flush
	 * temporary buffers, and restore the object to a clean state ready for reuse.
	 * Unlike {@link Destroyable#destroy()}, this method prepares the object for
	 * continued use rather than final disposal.</p>
	 *
	 * <p>The default implementation does nothing, allowing implementations to
	 * only override when reset behavior is needed.</p>
	 *
	 * <p><strong>Implementation Guidelines:</strong></p>
	 * <ul>
	 *   <li>Make this method idempotent (safe to call multiple times)</li>
	 *   <li>Ensure the object is fully usable after reset</li>
	 *   <li>Clear transient state but preserve structural configuration</li>
	 *   <li>Document what state is preserved vs. cleared</li>
	 * </ul>
	 */
	default void reset() {

	}
}

