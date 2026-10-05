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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
	 * @param target The object to conditionally destroy
	 * @return The same object that was passed in (for chaining)
	 */
	static <T> T destroy(T target) {
		if (target instanceof Destroyable) {
			((Destroyable) target).destroy();
		}

		return target;
	}

	/**
	 * Runs a one-shot operation and then destroys it if it is {@link Destroyable}, whether or
	 * not running it succeeds.
	 *
	 * <p>Failures are aggregated as by {@link #releaseAll(Iterable)}: if both running and
	 * destroying fail, the run failure is rethrown with the destroy failure attached as
	 * suppressed, so the reason the operation failed is never replaced by a cleanup failure.</p>
	 *
	 * <p>A compiled operation that is run exactly once — initializing a weight, for example —
	 * otherwise keeps its argument and native resources until it is garbage collected:</p>
	 * <pre>{@code
	 * Destroyable.runOnce(a(cp(weight.each()), values.each()).get());
	 * }</pre>
	 *
	 * @param operation the operation to run and then destroy
	 */
	static void runOnce(Runnable operation) {
		releaseAll(List.of(operation), () -> destroy(operation));
	}

	/**
	 * Destroys all {@link Destroyable} elements in the given iterable.
	 *
	 * @param targets the iterable of objects to destroy
	 * @param <T>     the element type
	 * @return the number of elements that were destroyed
	 */
	static <T> int destroy(Iterable<T> targets) {
		if (targets == null) return 0;

		int destroyed = 0;

		for (T target : targets) {
			if (target instanceof Destroyable) {
				destroy(target);
				destroyed++;
			}
		}

		return destroyed;
	}

	/**
	 * Runs every release action in the given iterable, even when earlier ones fail.
	 *
	 * <p>Cleanup that frees several independent resources must not let one failing release
	 * leave the rest allocated. Every action is attempted; the first unchecked failure
	 * ({@link RuntimeException} or {@link Error}) is rethrown once all have run, with any
	 * later ones attached to it as suppressed. {@link Error}s are aggregated too because
	 * callers such as Metal completion-callback draining run arbitrary actions whose
	 * {@link AssertionError}, {@link LinkageError}, or {@link OutOfMemoryError} must not
	 * skip the releases owned by subsequent actions.</p>
	 *
	 * @param releases the release actions to run, in iteration order; null is treated as empty
	 * @throws RuntimeException the first failure raised by any action, if it is a {@link RuntimeException}
	 * @throws Error the first failure raised by any action, if it is an {@link Error}
	 */
	static void releaseAll(Iterable<? extends Runnable> releases) {
		releaseAll(releases, new Runnable[0]);
	}

	/**
	 * Destroys every target, best-effort, attaching any release failure to the given primary
	 * throwable as a suppressed exception rather than throwing it.
	 *
	 * <p>This is the rollback-on-failure form of {@link #destroy(Iterable)}. A caller handling a
	 * construction failure {@code t} releases the resources it had already allocated with
	 * {@code destroyAll(t, resources)} and then rethrows {@code t}, so the original failure is
	 * preserved and a release that itself throws neither aborts the remaining releases nor masks
	 * {@code t}. Unlike {@link #destroy(Iterable)}, one failing {@link #destroy()} does not leave
	 * the rest of the targets allocated.</p>
	 *
	 * @param primary the failure being rolled back, onto which release failures are suppressed;
	 *                must not be null
	 * @param targets the objects to conditionally destroy, in iteration order; null is treated as
	 *                empty, and non-{@link Destroyable} entries are skipped
	 */
	static void destroyAll(Throwable primary, Iterable<?> targets) {
		if (targets == null) return;

		for (Object target : targets) {
			try {
				destroy(target);
			} catch (RuntimeException | Error e) {
				if (e != primary) primary.addSuppressed(e);
			}
		}
	}

	/**
	 * Destroys every target, best-effort, attaching any release failure to the given primary
	 * throwable as a suppressed exception rather than throwing it.
	 *
	 * @param primary the failure being rolled back, onto which release failures are suppressed;
	 *                must not be null
	 * @param targets the objects to conditionally destroy, in order; null is treated as empty
	 * @see #destroyAll(Throwable, Iterable)
	 */
	static void destroyAll(Throwable primary, Object... targets) {
		if (targets != null) destroyAll(primary, Arrays.asList(targets));
	}

	/**
	 * Runs every release action in the given iterable and then each of the further actions,
	 * even when earlier ones fail, with the same failure aggregation as
	 * {@link #releaseAll(Iterable)}.
	 *
	 * <p>This replaces a {@code try}/{@code finally} around a group of releases followed by one
	 * more: {@code releaseAll(callbacks, buffer::release)} runs the callbacks and then releases
	 * the buffer, whichever of them throws.</p>
	 *
	 * @param releases the release actions to run first, in iteration order; null is treated as empty
	 * @param more     further release actions to run after them, in order
	 * @throws RuntimeException the first failure raised by any action, if it is a {@link RuntimeException}
	 * @throws Error the first failure raised by any action, if it is an {@link Error}
	 */
	static void releaseAll(Iterable<? extends Runnable> releases, Runnable... more) {
		List<Runnable> all = new ArrayList<>();
		if (releases != null) releases.forEach(all::add);
		all.addAll(Arrays.asList(more));

		Throwable failure = null;

		for (Runnable release : all) {
			try {
				release.run();
			} catch (RuntimeException | Error e) {
				if (failure == null) {
					failure = e;
				} else if (failure != e) {
					failure.addSuppressed(e);
				}
			}
		}

		if (failure instanceof RuntimeException) throw (RuntimeException) failure;
		if (failure instanceof Error) throw (Error) failure;
	}
}

