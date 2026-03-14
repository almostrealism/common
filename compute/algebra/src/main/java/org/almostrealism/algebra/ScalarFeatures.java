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

package org.almostrealism.algebra;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.Choice;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * Provides convenient factory methods for creating scalar computations.
 *
 * <p>
 * {@link ScalarFeatures} extends {@link CollectionFeatures} to provide specialized methods
 * for working with scalar values in the computation graph framework. This interface is
 * designed to be mixed into classes that need to create scalar computations.
 * </p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * public class MyComputation implements ScalarFeatures {
 *     public Producer<PackedCollection> compute() {
 *         // Create constant scalar (size 1)
 *         CollectionProducer<?> s1 = scalar(5.0);
 *
 *         // Scalar operations
 *         return s1.add(scalar(10.0));
 *     }
 * }
 * }</pre>
 *
 * @author  Michael Murray
 * @see CollectionFeatures
 * @see CollectionProducer
 */
public interface ScalarFeatures extends CollectionFeatures {

	/**
	 * Creates a {@link CollectionProducer} for a constant scalar value.
	 * Alias for {@link #c(double)}.
	 *
	 * @param value  the scalar value
	 * @return a producer for the constant scalar
	 */
	default CollectionProducer scalar(double value) { return c(value); }

	/**
	 * Creates a {@link Choice} computation that selects from multiple options based on a decision value.
	 *
	 * @param choiceCount   the number of choices available
	 * @param resultShape   the shape of the result
	 * @param decision      a producer providing the decision index
	 * @param choices       a producer providing the available choice values
	 * @return a choice computation
	 */
	default Choice choice(int choiceCount, TraversalPolicy resultShape,
						  Producer<PackedCollection> decision,
						  Producer<PackedCollection> choices) {
		return new Choice(resultShape, choiceCount, decision, choices);
	}

	/**
	 * Returns a singleton instance of {@link ScalarFeatures}.
	 *
	 * @return a ScalarFeatures instance
	 */
	static ScalarFeatures getInstance() { return new ScalarFeatures() { }; }
}
