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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
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
	 * <p>Every candidate's rank and value are part of the graph. The selection is an
	 * element-wise fold over the candidates that keeps the value with the greatest key
	 * (the negated rank, or a miss key below the blank value's key when the rank is under the
	 * epsilon threshold), so the result compiles into a single kernel rather than evaluating
	 * each rank on the host. Because every step is element-wise, each item of a batch (each
	 * pixel of a render, for example) makes its own selection from its own ranks and values. Unlike
	 * {@link #evaluate(Object[])}, a missing selection yields the blank value regardless of
	 * {@link #tolerateNull}.</p>
	 *
	 * @param memLength the number of elements of each candidate's value
	 * @return a producer of the closest candidate's value
	 */
	public CollectionProducer getClosestChoice(int memLength) {
		ScalarFeatures f = ScalarFeatures.getInstance();

		CollectionProducer bestKey = f.c(BLANK_KEY);
		CollectionProducer best = f.zeros(new TraversalPolicy(memLength));

		for (int i = 0; i < size(); i++) {
			CollectionProducer rank = f.c(get(i).getRank());
			CollectionProducer key = f.greaterThan(rank, f.c(e), rank.minus(), f.c(MISS_KEY), true);

			// Strictly greater, so that a tie keeps the earlier candidate
			best = f.greaterThan(key, bestKey, (Producer) get(i).getProducer(), best);
			bestKey = f.max(key, bestKey);
		}

		return best;
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
		CollectionProducer closest = getClosestChoice(memLength);
		TraversalPolicy shape = closest.getShape();

		DefaultTraversableExpressionComputation accelerated = new DefaultTraversableExpressionComputation(
				"closestChoice", shape, args -> CollectionExpression.create(shape, args[1]::getValueAt), closest);
		if (postprocessor != null) accelerated.setPostprocessor((BiFunction) postprocessor);
		return (Evaluable) accelerated.get();
	}
}
