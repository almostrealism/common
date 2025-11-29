/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.HeredityFeatures;
import org.almostrealism.heredity.ProbabilisticFactory;
import org.almostrealism.physics.Atom;

import java.util.List;

/**
 * Represents a probabilistic mixture of atomic substances (an alloy).
 *
 * <p>An {@code Alloy} is a material composed of multiple {@link Atomic} components
 * mixed according to specified proportions. When {@link #construct()} is called,
 * the alloy probabilistically selects which component element to use based on
 * the composition ratios, then constructs an atom of that element.</p>
 *
 * <p>This class extends {@link ProbabilisticFactory} to provide genetic algorithm
 * support through the heredity module, enabling evolution of material compositions.</p>
 *
 * <h2>Creating Common Alloys</h2>
 * <pre>{@code
 * // Bronze: 88% Copper, 12% Tin
 * Alloy bronze = new Alloy(
 *     Arrays.asList(PeriodicTable.Copper, PeriodicTable.Tin),
 *     0.88, 0.12
 * );
 *
 * // Steel: 98% Iron, 1.5% Carbon, 0.5% Manganese
 * Alloy steel = new Alloy(
 *     Arrays.asList(PeriodicTable.Iron, PeriodicTable.Carbon, PeriodicTable.Manganese),
 *     0.98, 0.015, 0.005
 * );
 *
 * // Brass: 70% Copper, 30% Zinc
 * Alloy brass = new Alloy(
 *     Arrays.asList(PeriodicTable.Copper, PeriodicTable.Zinc),
 *     0.70, 0.30
 * );
 * }</pre>
 *
 * <h2>Generating Atoms from an Alloy</h2>
 * <pre>{@code
 * Alloy bronze = new Alloy(
 *     Arrays.asList(PeriodicTable.Copper, PeriodicTable.Tin),
 *     0.88, 0.12
 * );
 *
 * // Generate atoms - each call probabilistically selects Cu or Sn
 * for (int i = 0; i < 1000; i++) {
 *     Atom atom = bronze.construct();
 *     // Approximately 880 atoms will be Cu, 120 will be Sn
 * }
 * }</pre>
 *
 * @see Atomic
 * @see Element
 * @see PeriodicTable
 * @see org.almostrealism.heredity.ProbabilisticFactory
 *
 * @author Michael Murray
 */
public class Alloy extends ProbabilisticFactory<Atom> implements Atomic {

    /**
     * Creates an empty alloy with no components.
     *
     * <p>Components must be added separately before this alloy can construct atoms.</p>
     */
    public Alloy() { }

    /**
     * Creates an alloy with the specified components and composition ratios.
     *
     * <p>The composition values should correspond to the probability of selecting
     * each component. They do not need to sum to 1.0 - they will be normalized
     * internally.</p>
     *
     * @param components the list of atomic substances that make up this alloy
     * @param g          the composition ratios for each component (varargs)
     *
     * @throws IllegalArgumentException if components and ratios have different sizes
     *
     * @see PeriodicTable
     */
    @SuppressWarnings("unchecked")
    public Alloy(List<Atomic> components, double... g) {
        this(components, HeredityFeatures.getInstance().g(g));
    }

    /**
     * Creates an alloy with the specified components and a Gene-based composition.
     *
     * <p>This constructor allows the alloy composition to be controlled by a
     * {@link Gene}, enabling integration with genetic algorithms for evolving
     * material compositions.</p>
     *
     * @param components  the list of atomic substances that make up this alloy
     * @param composition a Gene containing the composition ratios
     *
     * @see org.almostrealism.heredity.Gene
     */
    public Alloy(List<Atomic> components, Gene<PackedCollection> composition) {
        super(components, composition);
    }
}
