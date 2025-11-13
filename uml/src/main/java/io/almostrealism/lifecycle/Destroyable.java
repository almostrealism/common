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

package io.almostrealism.lifecycle;

/**
 * A lifecycle interface for objects that require explicit cleanup of resources.
 *
 * <p>This interface extends {@link AutoCloseable} to provide resource cleanup semantics
 * compatible with try-with-resources statements, while offering a more semantically
 * meaningful {@link #destroy()} method name for resource deallocation.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code Destroyable} is designed for objects that manage:</p>
 * <ul>
 *   <li><strong>Native Resources:</strong> GPU memory, OpenCL buffers, native handles</li>
 *   <li><strong>Hardware Acceleration:</strong> Compiled kernels, device contexts</li>
 *   <li><strong>Off-Heap Memory:</strong> Direct buffers, memory-mapped files</li>
 *   <li><strong>External Connections:</strong> Device handles, system resources</li>
 * </ul>
 *
 * <h2>Relationship with AutoCloseable</h2>
 * <p>The {@link #close()} method delegates to {@link #destroy()}, allowing instances
 * to be used in try-with-resources statements while providing a more descriptive
 * method name for manual cleanup:</p>
 * <pre>{@code
 * // Try-with-resources (calls close(), which calls destroy())
 * try (MyDestroyableResource resource = new MyDestroyableResource()) {
 *     resource.doWork();
 * } // destroy() called automatically
 *
 * // Manual cleanup (more explicit)
 * MyDestroyableResource resource = new MyDestroyableResource();
 * try {
 *     resource.doWork();
 * } finally {
 *     resource.destroy();
 * }
 * }</pre>
 *
 * <h2>Default Implementation</h2>
 * <p>The default {@link #destroy()} implementation is a no-op, allowing implementations
 * to only override it when actual cleanup is needed. This follows the pattern of
 * optional resource management.</p>
 *
 * <h2>Static Helper Method</h2>
 * <p>The {@link #destroy(Object)} static method provides a convenient way to
 * conditionally destroy objects that may or may not implement {@code Destroyable}:</p>
 * <pre>{@code
 * // Safe cleanup regardless of type
 * Object resource = getResource();
 * Destroyable.destroy(resource);  // Only destroys if it's Destroyable
 * }</pre>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic implementation:</strong></p>
 * <pre>{@code
 * public class GPUBuffer implements Destroyable {
 *     private CLBuffer buffer;
 *
 *     @Override
 *     public void destroy() {
 *         if (buffer != null) {
 *             buffer.release();
 *             buffer = null;
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Try-with-resources usage:</strong></p>
 * <pre>{@code
 * try (GPUBuffer buffer = new GPUBuffer(size)) {
 *     buffer.write(data);
 *     kernel.execute(buffer);
 * } // buffer.destroy() called automatically
 * }</pre>
 *
 * <p><strong>Conditional destruction:</strong></p>
 * <pre>{@code
 * List<Object> resources = Arrays.asList(buffer1, object2, buffer3);
 * resources.forEach(Destroyable::destroy);  // Only destroys Destroyable objects
 * }</pre>
 *
 * @see AutoCloseable
 * @see Lifecycle
 */
public interface Destroyable extends AutoCloseable {
	/**
	 * Releases resources associated with this object.
	 *
	 * <p>Implementations should release all external resources such as native memory,
	 * GPU buffers, device handles, or file descriptors. This method should be
	 * idempotent - calling it multiple times should be safe and have no effect
	 * after the first call.</p>
	 *
	 * <p>The default implementation does nothing, allowing implementations to
	 * only override when cleanup is needed.</p>
	 *
	 * <p><strong>Implementation Guidelines:</strong></p>
	 * <ul>
	 *   <li>Make this method idempotent (safe to call multiple times)</li>
	 *   <li>Set resource references to null after cleanup</li>
	 *   <li>Handle cleanup failures gracefully</li>
	 *   <li>Don't throw checked exceptions (use runtime exceptions if needed)</li>
	 * </ul>
	 */
	default void destroy() { }

	/**
	 * Closes this resource by delegating to {@link #destroy()}.
	 *
	 * <p>This method enables {@code Destroyable} instances to be used in
	 * try-with-resources statements. It simply calls {@link #destroy()},
	 * allowing the more semantically meaningful method name to be used
	 * for manual cleanup.</p>
	 *
	 * <p>Note: Unlike {@link AutoCloseable#close()}, this method does not
	 * throw checked exceptions.</p>
	 */
	@Override
	default void close() { destroy(); }

	/**
	 * Conditionally destroys an object if it implements {@link Destroyable}.
	 *
	 * <p>This static helper method provides a safe way to destroy objects that
	 * may or may not implement the {@code Destroyable} interface. If the object
	 * is {@code Destroyable}, its {@link #destroy()} method is called. Otherwise,
	 * the object is simply returned unchanged.</p>
	 *
	 * <p>This pattern is useful when working with collections of mixed types or
	 * when the destroyability of an object is determined at runtime:</p>
	 * <pre>{@code
	 * // Clean up a list that may contain both Destroyable and non-Destroyable objects
	 * resources.forEach(Destroyable::destroy);
	 * }</pre>
	 *
	 * @param <T> The type of the object
	 * @param destroyable The object to conditionally destroy
	 * @return The same object that was passed in (for chaining)
	 */
	static <T> T destroy(T destroyable) {
		if (destroyable instanceof Destroyable) {
			((Destroyable) destroyable).destroy();
		}

		return destroyable;
	}
}

