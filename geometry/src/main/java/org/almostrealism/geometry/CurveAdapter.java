/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;

/**
 * An abstract base class that provides a convenient adapter implementation of {@link Curve}.
 * This class simplifies implementing curves by providing utility methods from {@link VectorFeatures}
 * and a convenience method for evaluating the curve at a specific point.
 *
 * <p>Subclasses need only implement the {@link #getValueAt(Producer)} and
 * {@link #getNormalAt(Producer)} methods from the parent interfaces.</p>
 *
 * @param <T> the type of value returned by this curve
 * @author Michael Murray
 * @see Curve
 */
public abstract class CurveAdapter<T> implements Curve<T>, VectorFeatures {
	/**
	 * Evaluates this curve at the specified vector position.
	 * This is a convenience method that wraps the vector and evaluates the curve.
	 *
	 * @param v the position at which to evaluate the curve
	 * @return the value of this curve at the specified position
	 */
	public T operate(Vector v) { return getValueAt(v(v)).get().evaluate(); }
}
