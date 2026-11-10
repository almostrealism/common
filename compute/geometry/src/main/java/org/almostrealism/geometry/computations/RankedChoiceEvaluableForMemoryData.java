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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.computations.Choice;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;

import java.util.function.BiFunction;

/**
 * An abstract extension of {@link RankedChoiceEvaluable} for memory-based data types.
 * This class provides the foundation for hardware-accelerated ranked choice operations
 * on types that implement {@link MemoryData}.
 *
 * <p>{@link #getClosestChoice(int)} expresses the same selection as
 * {@link RankedChoiceEvaluable#evaluate(Object[])} (the candidate with the smallest rank that
 * is at least the epsilon threshold, the first one on a tie) as a single
 * {@link CollectionProducer} graph, so that it compiles into one kernel and can be embedded
 * in a larger computation, such as the colour of a reflected ray inside a shader.
 * {@link #getAccelerated(int, BiFunction)} compiles that graph.</p>
 *
 * @param <T> the type of memory data produced
 * @author Michael Murray
 * @see RankedChoiceEvaluable
 * @see MemoryData
 */
public abstract class RankedChoiceEvaluableForMemoryData<T extends MemoryData> extends RankedChoiceEvaluable<T> implements Evaluable<T> {
	/**
	 * The selection key of a candidate whose rank is below the epsilon threshold. It is
	 * lower than {@link #BLANK_KEY}, so such a candidate is never chosen. Both keys are
	 * representable in single precision.
	 */
	private static final double MISS_KEY = -1.0e30;

	/**
	 * The selection key of the blank (all zero) value, which is chosen only when no
	 * candidate has a rank at least the epsilon threshold.
	 */
	private static final double BLANK_KEY = -1.0e29;

	/**
	 * Constructs a RankedChoiceEvaluableForMemoryData with the specified epsilon.
	 *
	 * @param e the epsilon threshold
	 */
	public RankedChoiceEvaluableForMemoryData(double e) {
		super(e);
	}

	/**
	 * Constructs a RankedChoiceEvaluableForMemoryData with the specified epsilon.
	 *
	 * @param e the epsilon threshold
	 * @param tolerateNull whether to allow null results
	 */
	public RankedChoiceEvaluableForMemoryData(double e, boolean tolerateNull) {
		super(e, tolerateNull);
	}

	/**
	 * Returns a {@link CollectionProducer} that selects the value of the candidate with the
	 * smallest rank that is at least the epsilon threshold, or a blank (all zero) value of
	 * {@code memLength} elements when there is no such candidate.
	 *
	 * <p>Every candidate's rank and value are part of the graph, and the selection is made
	 * with an index-of-maximum over the negated ranks followed by a {@link Choice}, so the result compiles into a
	 * single kernel rather than evaluating each rank on the host. Unlike
	 * {@link #evaluate(Object[])}, a missing selection yields the blank value regardless of
	 * {@link #tolerateNull}.</p>
	 *
	 * @param memLength the number of elements of each candidate's value
	 * @return a producer of the closest candidate's value
	 */
	public CollectionProducer getClosestChoice(int memLength) {
		if (isEmpty()) return ScalarFeatures.getInstance().zeros(new TraversalPolicy(memLength));
		return closestChoice(memLength);
	}

	/**
	 * Builds the {@link Choice} computation behind {@link #getClosestChoice(int)}.
	 *
	 * @param memLength the number of elements of each candidate's value
	 * @return the choice of the closest candidate's value
	 */
	private Choice closestChoice(int memLength) {
		ScalarFeatures f = ScalarFeatures.getInstance();
		TraversalPolicy valueShape = f.shape(1, memLength);

		int count = size() + 1;
		Producer<PackedCollection>[] keys = new Producer[count];
		Producer<PackedCollection>[] values = new Producer[count];

		for (int i = 0; i < size(); i++) {
			CollectionProducer rank = f.c(f.reshape(f.shape(1), get(i).getRank()));
			keys[i] = f.greaterThan(rank, f.c(e), rank.minus(), f.c(MISS_KEY), true);
			values[i] = f.reshape(valueShape, get(i).getProducer());
		}

		keys[count - 1] = f.c(BLANK_KEY);
		values[count - 1] = f.zeros(valueShape);

		CollectionProducer decision = f.indexOfMax(f.concat(f.shape(count), keys))
				.add(0.5).divide(count);
		return new Choice(f.shape(memLength), count, decision,
				f.concat(f.shape(count, memLength), values));
	}

	/**
	 * Returns a hardware-accelerated version of this evaluable: the compiled
	 * {@link #getClosestChoice(int)}, whose output is converted by the given post-processor.
	 *
	 * @param memLength the memory length of the output type
	 * @param postprocessor converts the output memory at an offset into the output type, or
	 *                      {@code null} when the output type is {@link PackedCollection} itself
	 * @return an accelerated evaluable
	 */
	public Evaluable<T> getAccelerated(int memLength, BiFunction<MemoryData, Integer, T> postprocessor) {
		Choice choice = closestChoice(memLength);
		if (postprocessor != null) choice.setPostprocessor((BiFunction) postprocessor);
		return (Evaluable) choice.get();
	}
}
