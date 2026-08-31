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

package org.almostrealism.algebra;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * A utility class providing access to the zero vector (0, 0, 0).
 *
 * <p>
 * {@link ZeroVector} is a singleton-like utility that provides {@link Producer} and
 * {@link Evaluable} instances for the zero vector. This is commonly used as a default
 * or initial value in vector computations.
 * </p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Get a producer for the zero vector
 * Producer<PackedCollection> zeroProducer = ZeroVector.getInstance();
 *
 * // Get an evaluable for direct evaluation
 * Evaluable<Vector> zeroEval = ZeroVector.getEvaluable();
 * Vector zero = zeroEval.evaluate();  // Vector(0, 0, 0)
 * }</pre>
 *
 * @author  Michael Murray
 * @see Vector
 * @see UnityVector
 */
public class ZeroVector {

	/**
	 * Private constructor to prevent instantiation.
	 */
	private ZeroVector() { }

	/**
	 * Returns a {@link Producer} that generates the zero vector (0, 0, 0).
	 *
	 * <p>Described as zeros rather than as a {@link Vector} of three of them.
	 * Building one names the value on the host and writes all three across,
	 * where zeroing is the one case a kernel expresses on its own — and it is
	 * this vector, wanted wherever something needs an origin or a default
	 * direction, that pays for it most often.</p>
	 *
	 * @return a producer for the zero vector
	 */
	public static Producer<PackedCollection> getInstance() {
		return VectorFeatures.getInstance().zeros(Vector.shape());
	}

	/**
	 * Returns an {@link Evaluable} that produces the zero vector (0, 0, 0).
	 *
	 * @return an evaluable for the zero vector
	 */
	public static Evaluable<PackedCollection> getEvaluable() { return getInstance().get(); }
}
