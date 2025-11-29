/*
 * Copyright 2024 Michael Murray
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
import io.almostrealism.relation.ProducerWithRank;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.computations.HighestRank;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Input;

import java.util.ArrayList;

/**
 * An evaluable that selects from multiple producers based on their rank values.
 * This is used in ray tracing to select the closest intersection from multiple
 * candidates, where "rank" typically represents distance.
 *
 * <p>The evaluable iterates through all candidates and returns the result from
 * the producer with the smallest rank value that is still >= the epsilon threshold.
 * This allows filtering out invalid or negative intersections.</p>
 *
 * @param <T> the type of value produced
 * @author Michael Murray
 * @see ProducerWithRank
 */
public class RankedChoiceEvaluable<T> extends ArrayList<ProducerWithRank<T, PackedCollection>> implements Evaluable<T> {
	/** The epsilon threshold - ranks below this value are considered invalid. */
	protected double e;
	/** Whether to allow returning null if no valid candidate is found. */
	protected boolean tolerateNull;

	/** Precompiled evaluable for finding the highest rank between two candidates. */
	public static final Evaluable<Pair> highestRank;

	static {
		TraversalPolicy inputShape =
				new TraversalPolicy(false, false, 2);
		highestRank = (Evaluable<Pair>) (Evaluable) new HighestRank(Input.value(inputShape, 0),
				Input.value(inputShape, 1)).get();
	}

	/**
	 * Constructs a RankedChoiceEvaluable with the specified epsilon threshold.
	 * Null results are tolerated by default.
	 *
	 * @param e the epsilon threshold - ranks below this are considered invalid
	 */
	public RankedChoiceEvaluable(double e) { this(e, true); }

	/**
	 * Constructs a RankedChoiceEvaluable with the specified epsilon threshold.
	 *
	 * @param e the epsilon threshold - ranks below this are considered invalid
	 * @param tolerateNull if false, throws exception when no valid candidate is found
	 */
	public RankedChoiceEvaluable(double e, boolean tolerateNull) { this.e = e; this.tolerateNull = tolerateNull; }

	/**
	 * Returns the epsilon threshold value.
	 *
	 * @return the epsilon threshold
	 */
	public double getEpsilon() { return e; }

	/**
	 * Evaluates all candidates and returns the result from the one with the best
	 * (smallest positive) rank value.
	 *
	 * @param args the arguments to pass to the evaluation
	 * @return the result from the best-ranked producer, or null if none found
	 * @throws NullPointerException if tolerateNull is false and no valid candidate exists
	 */
	@Override
	public T evaluate(Object[] args) {
		Producer<T> best = null;
		double rank = Double.MAX_VALUE;

		boolean printLog = false; // Math.random() < 0.04;

		if (printLog) {
			System.out.println("RankedChoiceProducer: There are " + size() + " Producers to choose from");
		}

		r: for (ProducerWithRank<T, PackedCollection> p : this) {
			PackedCollection rs = p.getRank().get().evaluate(args);
			if (rs == null) continue r;

			double r = rs.toDouble(0);
			if (r < e && printLog) System.out.println(p + " was skipped due to being less than " + e);
			if (r < e) continue r;

			if (best == null) {
				if (printLog) System.out.println(p + " was assigned (rank = " + r + ")");
				best = p.getProducer();
				rank = r;
			} else {
				if (r >= e && r < rank) {
					if (printLog) System.out.println(p + " was assigned (rank = " + r + ")");
					best = p.getProducer();
					rank = r;
				}
			}

			if (rank <= e) break r;
		}

		if (printLog) System.out.println(best + " was chosen\n----------");

		if (best == null && !tolerateNull) {
			throw new NullPointerException("Nothing selected by RankedChoiceProducer");
		}

		return best == null ? null : best.get().evaluate(args);
	}
}
