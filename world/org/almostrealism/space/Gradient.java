/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.relation.TripleFunction;
import org.almostrealism.algebra.Differentiable;
import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.Vector;
import io.almostrealism.relation.Producer;
import org.almostrealism.uml.Function;

/**
 * A {@link Gradient} represents any continuously evaluable {@link TripleFunction}
 * that has a normal {@link Vector} for each {@link org.almostrealism.algebra.Triple}.
 * 
 * @author  Michael Murray
 */
@Function
public interface Gradient<T> extends Differentiable<T> {
	/**
	 * Returns a {@link Vector} that represents the normal to the surface at the point
	 * represented by the specified {@link Triple}.
	 */
	Producer<Vector> getNormalAt(Producer<Vector> point);
}
