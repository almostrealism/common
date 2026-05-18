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
 * Foundational lifecycle interfaces for resource management and object state control.
 *
 * <p>This package provides the core lifecycle contracts used throughout the AR framework
 * to manage object state transitions, resource allocation, and cleanup. Three interfaces
 * cover the primary lifecycle concerns:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.lifecycle.Destroyable} — for objects that hold external
 *       resources (GPU memory, native handles, connections) and require explicit cleanup.
 *       Extends {@link java.lang.AutoCloseable} for try-with-resources compatibility.</li>
 *   <li>{@link io.almostrealism.lifecycle.Lifecycle} — for objects that accumulate transient
 *       state and need periodic reinitialization without full destruction.</li>
 *   <li>{@link io.almostrealism.lifecycle.Setup} — for objects that require a one-time
 *       setup phase before entering normal operation, supplying a {@link java.lang.Runnable}
 *       that executes the initialization logic.</li>
 * </ul>
 *
 * <p>These interfaces have no external dependencies and form the absolute foundation of
 * the {@code base/meta} module, which itself has no transitive dependencies on other
 * AR modules.</p>
 */
package io.almostrealism.lifecycle;
