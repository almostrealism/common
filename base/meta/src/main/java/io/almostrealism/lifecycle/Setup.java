/*
 * Copyright 2021 Michael Murray
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

import java.util.function.Supplier;

/**
 * A lifecycle interface for objects that require a one-time setup phase before use.
 *
 * <p>Implementations supply a {@link java.util.function.Supplier} that, when invoked,
 * produces a {@link Runnable} which performs the actual initialization work. This
 * two-level indirection allows the setup logic to be captured lazily and executed
 * at the appropriate point in the object's lifecycle.</p>
 *
 * <p>Typical usage involves calling {@link #setup()} to obtain the supplier, then
 * invoking the supplier to produce the setup runnable, and finally running that
 * runnable to apply initialization:</p>
 * <pre>{@code
 * Setup component = ...;
 * Runnable init = component.setup().get();
 * init.run();
 * }</pre>
 */
public interface Setup {
	/**
	 * Returns a supplier that, when invoked, produces the {@link Runnable} responsible
	 * for initializing this object.
	 *
	 * <p>The returned supplier may be called multiple times; implementations should ensure
	 * that each produced {@link Runnable} either applies initialization idempotently or
	 * guards against duplicate initialization internally.</p>
	 *
	 * @return a {@link java.util.function.Supplier} that yields the setup {@link Runnable}
	 */
	Supplier<Runnable> setup();
}
