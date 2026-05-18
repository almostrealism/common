/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.heredity.TemporalCellular;

import java.util.function.Supplier;

/**
 * A collection of audio processing cells that can be executed for a specified duration.
 *
 * <p>Cells combines the temporal execution capabilities of {@link TemporalCellular} with
 * the audio processing utilities of {@link CellFeatures}, providing convenient methods
 * for running audio processing for specified time periods.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Cells cells = ... // obtain Cells instance
 *
 * // Run processing for 30 seconds
 * cells.sec(30).get().run();
 *
 * // Run processing for 2 minutes
 * cells.min(2).get().run();
 *
 * // Run for 10 seconds with reset after completion
 * cells.sec(10, true).get().run();
 * }</pre>
 *
 * @see TemporalCellular
 * @see CellFeatures
 * @see CellList
 */
public interface Cells extends TemporalCellular, CellFeatures, Iterable<Cell<PackedCollection>> {
	default Supplier<Runnable> min(double minutes) { return min(this, minutes); }

	default Supplier<Runnable> sec(double seconds) { return sec(this, seconds); }

	default Supplier<Runnable> sec(double seconds, boolean resetAfter) { return sec(this, seconds, resetAfter); }
}
