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

public interface Cells extends TemporalCellular, CellFeatures, Iterable<Cell<PackedCollection>> {
	default Supplier<Runnable> min(double minutes) { return min(this, minutes); }

	default Supplier<Runnable> sec(double seconds) { return sec(this, seconds); }

	default Supplier<Runnable> sec(double seconds, boolean resetAfter) { return sec(this, seconds, resetAfter); }
}
