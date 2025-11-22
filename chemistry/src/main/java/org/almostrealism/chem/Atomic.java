/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.chem;

import org.almostrealism.physics.Atom;
import io.almostrealism.relation.Factory;

/**
 * Interface for chemical substances that can construct atomic representations.
 *
 * <p>{@code Atomic} extends both {@link Factory} and {@link Substance}, providing
 * the capability to create {@link Atom} instances with proper electron shell
 * configurations. This is the bridge between the chemistry module (element definitions)
 * and the physics module (atomic structure).</p>
 *
 * <p>Implementations of this interface include:</p>
 * <ul>
 *   <li>{@link Element} - Individual chemical elements that construct atoms with
 *       their characteristic electron configurations</li>
 *   <li>{@link Alloy} - Probabilistic mixtures that randomly select which element's
 *       atom to construct based on composition ratios</li>
 * </ul>
 *
 * <p>The primary method inherited from {@link Factory} is {@code construct()},
 * which creates a new {@link Atom} instance:</p>
 * <pre>{@code
 * // Create a carbon atom with proper electron configuration
 * Atomic carbon = PeriodicTable.Carbon;
 * Atom carbonAtom = carbon.construct();
 *
 * // Create atoms from an alloy (probabilistically selects element)
 * Atomic bronze = new Alloy(Arrays.asList(PeriodicTable.Copper, PeriodicTable.Tin), 0.88, 0.12);
 * Atom atom = bronze.construct();  // 88% chance of Cu, 12% chance of Sn
 * }</pre>
 *
 * @see Element
 * @see Alloy
 * @see Substance
 * @see org.almostrealism.physics.Atom
 * @see io.almostrealism.relation.Factory
 *
 * @author Michael Murray
 */
public interface Atomic extends Factory<Atom>, Substance {

}
