/*
 * Copyright 2017 Michael Murray
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

package org.almostrealism.space;

import org.almostrealism.algebra.Ray;
import org.almostrealism.uml.Function;

/**
 * Similar to {@link Intersectable}, implementors of {@link DistanceEstimator} provide
 * an estimated distance along a {@link Ray} that could lead to an intersection.
 * 
 * @author Michael Murray
 */
@Function
public interface DistanceEstimator {
	public double estimateDistance(Ray r);
}
