/*
 * Copyright 2024 Michael Murray
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
 * Temporal cell implementations for time-based signal processing.
 *
 * <p>This package provides cells that process data over discrete time steps,
 * particularly for audio and signal processing. The cells integrate with the
 * {@link org.almostrealism.time.Temporal} interface for tick-based advancement.</p>
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link org.almostrealism.graph.temporal.CollectionTemporalCellAdapter} - Abstract
 *       base for collection-based temporal cells</li>
 *   <li>{@link org.almostrealism.graph.temporal.WaveCell} - Audio sample playback cell
 *       with amplitude scaling and frame windowing</li>
 *   <li>{@link org.almostrealism.graph.temporal.TemporalFactorFromCell} - Adapter that
 *       wraps a Cell as a CellularTemporalFactor for factor-chain integration</li>
 * </ul>
 *
 * @see org.almostrealism.graph.temporal.WaveCell
 * @see org.almostrealism.graph.temporal.CollectionTemporalCellAdapter
 */
package org.almostrealism.graph.temporal;
