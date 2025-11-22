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

package org.almostrealism.chem;

import io.almostrealism.relation.Node;

/**
 * Base interface for all chemical substances in the chemistry module.
 *
 * <p>A {@code Substance} represents any form of matter that can participate in
 * chemical processes. This includes individual elements, molecules, alloys,
 * and other chemical compounds. As a marker interface extending {@link Node},
 * it enables substances to participate in graph-based relationships and
 * reaction networks.</p>
 *
 * <p>The substance hierarchy is organized as follows:</p>
 * <ul>
 *   <li>{@link Atomic} - Substances that can construct atomic representations</li>
 *   <li>{@link Element} - Individual chemical elements from the periodic table</li>
 *   <li>{@link Molecule} - Compounds of multiple elements with graph structure</li>
 *   <li>{@link Alloy} - Probabilistic mixtures of atomic substances</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // All elements are substances
 * Substance carbon = PeriodicTable.Carbon;
 *
 * // Molecules are also substances
 * Substance water = new WaterMolecule();
 *
 * // Alloys are substances
 * Substance bronze = new Alloy(Arrays.asList(PeriodicTable.Copper, PeriodicTable.Tin), 0.88, 0.12);
 * }</pre>
 *
 * @see Atomic
 * @see Element
 * @see Molecule
 * @see Alloy
 * @see io.almostrealism.relation.Node
 *
 * @author Michael Murray
 */
public interface Substance extends Node {
}
