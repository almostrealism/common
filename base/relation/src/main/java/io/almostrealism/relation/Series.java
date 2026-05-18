/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.relation;

import java.util.OptionalInt;

/**
 * An interface for types that represent sequential or periodic data series.
 *
 * <p>{@link Series} provides information about the characteristics of a data
 * series, including whether it is finite and its periodicity. This is useful
 * for optimization decisions based on data patterns.</p>
 *
 * <h2>Series Characteristics</h2>
 * <ul>
 *   <li><b>Finite:</b> Whether the series has a definite end</li>
 *   <li><b>Period:</b> The repeat interval for periodic series</li>
 *   <li><b>Constant:</b> A special case with period 1 (all values equal)</li>
 * </ul>
 *
 * <h2>Usage in Optimization</h2>
 * <p>Series information enables optimizations such as:</p>
 * <ul>
 *   <li>Loop unrolling for finite series</li>
 *   <li>Caching for periodic series</li>
 *   <li>Value propagation for constant series</li>
 * </ul>
 *
 * @see Countable
 *
 * @author Michael Murray
 */
public interface Series {
	/**
	 * Returns whether this series is finite.
	 *
	 * <p>A finite series has a definite end point, while an infinite
	 * series continues indefinitely.</p>
	 *
	 * @return {@code true} if the series is finite
	 */
	boolean isFinite();

	/**
	 * Returns the period of this series, if it is periodic.
	 *
	 * <p>For a periodic series, the period indicates after how many
	 * elements the pattern repeats. A non-periodic series returns
	 * an empty optional.</p>
	 *
	 * @return the period if periodic, or empty if not periodic
	 */
	OptionalInt getPeriod();

	/**
	 * Returns whether this series is constant.
	 *
	 * <p>A constant series has period 1, meaning all elements have
	 * the same value.</p>
	 *
	 * @return {@code true} if the series is constant
	 */
	default boolean isConstant() { return getPeriod().isPresent() && getPeriod().getAsInt() == 1; }
}
