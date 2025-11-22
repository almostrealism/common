/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.geometry.computations;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.MemoryBank;

/**
 * A specialized {@link RankedChoiceEvaluableForMemoryData} for {@link Vector} values.
 * This class provides vector-specific acceleration and memory management.
 *
 * @author Michael Murray
 * @see RankedChoiceEvaluableForMemoryData
 * @see Vector
 */
public class RankedChoiceEvaluableForVector extends RankedChoiceEvaluableForMemoryData<Vector> {
	/**
	 * Constructs a RankedChoiceEvaluableForVector with the specified epsilon.
	 *
	 * @param e the epsilon threshold
	 */
	public RankedChoiceEvaluableForVector(double e) {
		super(e);
	}

	/**
	 * Constructs a RankedChoiceEvaluableForVector with the specified epsilon.
	 *
	 * @param e the epsilon threshold
	 * @param tolerateNull whether to allow null results
	 */
	public RankedChoiceEvaluableForVector(double e, boolean tolerateNull) {
		super(e, tolerateNull);
	}

	/**
	 * Returns a hardware-accelerated version configured for 3-component vectors.
	 *
	 * @return an accelerated evaluable for vectors
	 */
	public Evaluable<Vector> getAccelerated() {
		return getAccelerated(3, Vector::new, Vector::bank);
	}

	/**
	 * Creates a destination memory bank for storing vector results.
	 *
	 * @param size the number of vectors to store
	 * @return a new memory bank for vectors
	 */
	@Override
	public MemoryBank<Vector> createDestination(int size) { return Vector.bank(size); }
}
