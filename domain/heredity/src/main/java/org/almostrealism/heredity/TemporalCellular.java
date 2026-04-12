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

package org.almostrealism.heredity;

import org.almostrealism.time.Temporal;

/**
 * An interface combining {@link Temporal} and {@link Cellular} behaviors.
 *
 * <p>A {@code TemporalCellular} entity has all the capabilities of both interfaces:
 * <ul>
 *   <li>Time-stepped evolution through {@link Temporal#tick()}</li>
 *   <li>Graph connectivity through {@link Cellular}'s Node interface</li>
 *   <li>Setup and lifecycle management through {@link Cellular}'s Setup and Lifecycle interfaces</li>
 * </ul>
 *
 * <p>This is useful for cells in a computation graph that need to evolve over time
 * while maintaining proper lifecycle management and connectivity to other cells.
 *
 * <h2>Usage in Evolution</h2>
 * <p>In evolutionary computation, temporal cells can represent:
 * <ul>
 *   <li>Neural network layers that adapt over time</li>
 *   <li>Signal processing components with memory</li>
 *   <li>Stateful computational units in a graph</li>
 * </ul>
 *
 * @see Temporal
 * @see Cellular
 * @see CellularTemporalFactor
 */
public interface TemporalCellular extends Temporal, Cellular {
}
