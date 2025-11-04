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
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

public interface ScalarFeatures extends CollectionFeatures {

	/**
	 * Creates an {@link CollectionProducer} that produces a constant {@link Scalar} value.
	 * This method creates a computation that returns the values from the provided {@link Scalar},
	 * effectively creating a constant computation that always returns the same values.
	 * 
	 * @param value The {@link Scalar} containing the constant values
	 * @return An {@link CollectionProducer} that evaluates to the specified {@link Scalar}
	 */
	static CollectionProducer<Scalar> of(Scalar value) {
		return (CollectionProducer) DefaultTraversableExpressionComputation.fixed(value, Scalar.postprocessor());
	}

	default CollectionProducer<Scalar> v(Scalar value) { return value(value); }

	default CollectionProducer scalar(double value) { return c(value); }

	default CollectionProducer<Scalar> value(Scalar value) {
		return (CollectionProducer) DefaultTraversableExpressionComputation.fixed(value, Scalar.postprocessor());
	}

	default Producer<Scalar> scalar() {
		return Scalar.blank();
	}

	default Choice choice(int choiceCount, TraversalPolicy resultShape,
						  Producer<PackedCollection<?>> decision,
						  Producer<PackedCollection<?>> choices) {
		return new Choice(resultShape, choiceCount, decision, choices);
	}

	static ScalarFeatures getInstance() { return new ScalarFeatures() { }; }
}
