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

import org.almostrealism.algebra.Differentiable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.uml.Function;

/**
 * A {@link Gradient} represents any continuously evaluable function returning a {@link Vector}.
 * 
 * @author  Michael Murray
 */
@Function
public interface Gradient extends Differentiable<Vector> {
	/**
	 * Returns a {@link Vector} that represents the normal to the surface at the point
	 * represented by the specified {@link Vector}.
	 */
	VectorProducer getNormalAt(Vector point);
}
