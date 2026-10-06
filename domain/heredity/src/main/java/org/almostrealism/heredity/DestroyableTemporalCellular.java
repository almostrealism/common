/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.heredity;

import io.almostrealism.lifecycle.Destroyable;

/**
 * A {@link TemporalCellular} that additionally owns native resources the caller must release
 * with {@link Destroyable#destroy()}.
 *
 * <p>{@link TemporalCellular} alone describes a time-stepped cell pipeline but says nothing about
 * resource ownership, so a producer of a runner that compiles kernels and allocates device memory
 * cannot express the required cleanup through that type: a caller holding a {@code TemporalCellular}
 * would have to cast to {@link Destroyable} to free it. This interface makes that lifecycle visible
 * in the declared type, so a caller can release the runner directly — including via
 * try-with-resources through {@link Destroyable#close()} — without reaching around the interface to
 * the concrete implementation.</p>
 *
 * @see TemporalCellular
 * @see Destroyable
 */
public interface DestroyableTemporalCellular extends TemporalCellular, Destroyable {
}
