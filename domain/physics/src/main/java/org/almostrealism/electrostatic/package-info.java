/*
 * Copyright 2016 Michael Murray
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
 * Electrostatic potential modeling for atomic and molecular simulations.
 *
 * <p>This package models the electrostatic potential energy landscape
 * for charged particles. The central abstraction is the {@link org.almostrealism.electrostatic.PotentialMap},
 * which computes the potential at an arbitrary point in space.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.electrostatic.PotentialMap} - Interface for computing
 *       the electrostatic potential at a point in space</li>
 *   <li>{@link org.almostrealism.electrostatic.PotentialMapSet} - A collection of
 *       {@code PotentialMap} instances whose combined potential can be evaluated</li>
 *   <li>{@link org.almostrealism.electrostatic.ProtonCloud} - Coulomb potential
 *       for a positive point charge at the origin</li>
 *   <li>{@link org.almostrealism.electrostatic.PotentialMapHashSet} - HashSet-backed
 *       implementation of {@code PotentialMapSet}</li>
 *   <li>{@link org.almostrealism.electrostatic.SpanningTreePotentialMap} - Minimum
 *       spanning tree structure for efficient multi-charge potential evaluation</li>
 * </ul>
 */
package org.almostrealism.electrostatic;
