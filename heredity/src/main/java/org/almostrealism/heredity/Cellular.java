/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Node;
import io.almostrealism.lifecycle.Lifecycle;

/**
 * An interface representing a cell in a computation graph with lifecycle management.
 *
 * <p>A {@code Cellular} entity combines three key behaviors:
 * <ul>
 *   <li>{@link Node} - Can be connected to other nodes in a graph structure</li>
 *   <li>{@link Setup} - Has initialization/setup requirements</li>
 *   <li>{@link Lifecycle} - Has lifecycle events (reset, etc.)</li>
 * </ul>
 *
 * <p>This interface is used as a base for components that need to participate
 * in computational graphs while also supporting proper initialization and cleanup.
 *
 * <h2>Usage in Evolution</h2>
 * <p>In evolutionary algorithms, cells can represent computational units that
 * evolve over time. The lifecycle methods allow proper initialization before
 * each evaluation and cleanup/reset between generations.
 *
 * @see TemporalCellular
 * @see CellularTemporalFactor
 * @see Node
 * @see Setup
 * @see Lifecycle
 */
public interface Cellular extends Node, Setup, Lifecycle {
}
