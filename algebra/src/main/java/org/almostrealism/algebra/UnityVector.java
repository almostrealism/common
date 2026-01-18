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
 * A utility class providing access to the unity vector (1, 1, 1).
 *
 * <p>
 * {@link UnityVector} is a singleton-like utility that provides {@link Producer} and
 * {@link Evaluable} instances for the unity vector, which has all components set to 1.
 * This is commonly used for scaling operations, normalization, and as a multiplicative
 * identity in certain vector computations.
 * </p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Get a producer for the unity vector
 * Producer<PackedCollection> unityProducer = UnityVector.getInstance();
 *
 * // Get an evaluable for direct evaluation
 * Evaluable<Vector> unityEval = UnityVector.getEvaluable();
 * Vector unity = unityEval.evaluate();  // Vector(1, 1, 1)
 * }</pre>
 *
 * @author  Michael Murray
 * @see Vector
 * @see ZeroVector
 */
public class UnityVector {
	/**
	 * Private constructor to prevent instantiation.
	 */
	private UnityVector() { }

	/**
	 * Returns a {@link Producer} that generates the unity vector (1, 1, 1).
	 *
	 * @return a producer for the unity vector
	 */
	public static Producer<PackedCollection> getInstance() { return VectorFeatures.getInstance().vector(1.0, 1.0, 1.0); }

	/**
	 * Returns an {@link Evaluable} that produces the unity vector (1, 1, 1).
	 *
	 * @return an evaluable for the unity vector
	 */
	public static Evaluable<PackedCollection> getEvaluable() {
		return getInstance().get();
	}
}
