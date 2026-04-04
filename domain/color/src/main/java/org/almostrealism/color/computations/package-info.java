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

/**
 * Producer and evaluable implementations for color computations.
 *
 * <p>This package contains computation classes that participate in the Almost Realism
 * {@link io.almostrealism.relation.Producer} pipeline to generate, average, adapt, and
 * select {@link org.almostrealism.color.RGB} values during rendering and shading.</p>
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link org.almostrealism.color.computations.GeneratedColorProducer} — pairs a
 *       generator object with its resulting color producer for reflective inspection</li>
 *   <li>{@link org.almostrealism.color.computations.AverageColor} — computes a weighted
 *       average of multiple color samples</li>
 *   <li>{@link org.almostrealism.color.computations.RandomColorGenerator} — produces
 *       randomly-perturbed colors by offsetting a base color</li>
 *   <li>{@link org.almostrealism.color.computations.RankedChoiceEvaluableForRGB} — selects
 *       among multiple color producers using ranked-choice logic</li>
 *   <li>{@link org.almostrealism.color.computations.AdaptProducerRGB} — adapts a generic
 *       producer to return results in an RGB memory bank</li>
 * </ul>
 */
package org.almostrealism.color.computations;
