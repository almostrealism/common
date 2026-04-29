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

/**
 * Lifecycle utilities for managed values and weakly-referenced runnables in the AR framework.
 *
 * <p>This package provides concrete lifecycle helpers that complement the foundational interfaces
 * in {@code io.almostrealism.lifecycle}:</p>
 *
 * <ul>
 *   <li>{@link org.almostrealism.lifecycle.SuppliedValue} — a lazily initialized, optionally
 *       destroyable value holder that re-creates its value on demand using a {@link java.util.function.Supplier}.
 *       Supports custom validity checks and clear callbacks.</li>
 *   <li>{@link org.almostrealism.lifecycle.ThreadLocalSuppliedValue} — extends
 *       {@code SuppliedValue} to maintain a separate instance per thread, backed by a
 *       {@link java.util.WeakHashMap} so thread-local values are reclaimed automatically
 *       when their associated threads are garbage collected.</li>
 *   <li>{@link org.almostrealism.lifecycle.WeakRunnable} — a {@link java.lang.Runnable}
 *       that holds a {@link java.lang.ref.WeakReference} to a target object and applies
 *       a consumer to it when executed, allowing the target to be garbage collected if no
 *       other strong references exist.</li>
 * </ul>
 */
package org.almostrealism.lifecycle;
