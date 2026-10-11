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
 * A {@link TemporalCellular} that owns native or hardware-accelerated resources and can
 * release them through {@link Destroyable#destroy()}.
 *
 * <p>{@link TemporalCellular} combines {@link org.almostrealism.time.Temporal} and
 * {@link Cellular} to model a stateful, time-stepped cell; this interface adds the
 * lifecycle contract for the common case where such a cell is backed by GPU buffers,
 * compiled kernels, or producer threads. Exposing the destroy contract on the declared
 * type lets a holder release the resources without testing for {@link Destroyable} and
 * casting — the interface-bypass this interface exists to remove.</p>
 *
 * <p>The {@link Destroyable#destroy()} default is a no-op, so an implementation that owns
 * nothing beyond what its collaborators already release inherits a correct, empty
 * destruction.</p>
 *
 * @see TemporalCellular
 * @see Destroyable
 */
public interface DestroyableTemporalCellular extends TemporalCellular, Destroyable {
}
