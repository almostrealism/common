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

package org.almostrealism.geometry;

import io.almostrealism.relation.NodeList;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Gradient;

/**
 * A {@link DiscreteField} is a collection of points in space and corresponding directions,
 * represented as a list of {@link Ray} producers.
 *
 * <p>Unlike a {@link Gradient}, which can be continuously evaluated at any point in space,
 * a DiscreteField provides values only at specific discrete locations. Each point in the
 * field is represented as a {@link Ray} where:</p>
 * <ul>
 *   <li>The origin represents the position in space</li>
 *   <li>The direction represents the field direction at that point (e.g., surface normal)</li>
 * </ul>
 *
 * <p>Common uses include representing:</p>
 * <ul>
 *   <li>Surface intersection points with normals</li>
 *   <li>Sample points for Monte Carlo integration</li>
 *   <li>Point clouds with associated directions</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see ContinuousField
 * @see Ray
 */
public interface DiscreteField extends NodeList<Producer<Ray>> {

}
