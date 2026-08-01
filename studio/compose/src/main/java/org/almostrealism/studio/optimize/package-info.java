/*
 * Copyright 2026 Michael Murray
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

/**
 * Evolutionary optimization support for audio scene parameter tuning in the
 * Almost Realism studio layer. This package provides classes for population-based
 * genetic optimization of audio scene genomes using health computation metrics.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.optimize.AudioPopulationOptimizer} - Extends
 *       {@code PopulationOptimizer} to drive iterative optimization loops with WAV
 *       output and population persistence</li>
 *   <li>{@link org.almostrealism.studio.optimize.AudioSceneOptimizer} - High-level
 *       optimizer that wires an {@code AudioScene} to a population of genomes and
 *       coordinates optimization cycles</li>
 *   <li>{@link org.almostrealism.studio.optimize.AudioScenePopulation} - Population
 *       implementation that maintains a set of genomes and drives {@code AudioScene}
 *       rendering via genome assignment</li>
 *   <li>{@link org.almostrealism.studio.optimize.FixedFilterChromosome} - Chromosome
 *       that encodes high-pass and low-pass filter parameters for audio effects</li>
 *   <li>{@link org.almostrealism.studio.optimize.OptimizeFactorFeatures} - Mixin
 *       interface providing factor encoding/decoding helpers for genetic parameters
 *       including timing, speed, and polycyclic modulation</li>
 * </ul>
 */
package org.almostrealism.studio.optimize;
