/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph;

import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * A group of {@link CachedStateCell} instances that tick together as a unit.
 *
 * <p>{@code CachedStateCellGroup} collects multiple cached state cells and
 * implements {@link Temporal} to tick all of them in sequence. This is useful
 * when multiple cells need to be advanced in lockstep during temporal processing.</p>
 *
 * @param <T> the type of data processed by each cell
 * @see CachedStateCell
 * @see org.almostrealism.time.Temporal
 * @author Michael Murray
 */
public class CachedStateCellGroup<T> extends ArrayList<CachedStateCell<T>> implements Temporal {
	/**
	 * Ticks all cells in this group in sequence.
	 *
	 * @return a combined operation that ticks each cell in order
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("CachedStateCellGroup Tick");
		stream().map(CachedStateCell::tick).forEach(tick::add);
		return tick;
	}
}
