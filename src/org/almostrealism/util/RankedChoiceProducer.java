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

package org.almostrealism.util;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.relation.Evaluable;
import org.almostrealism.relation.Producer;

import java.util.ArrayList;

public class RankedChoiceProducer<T> extends ArrayList<ProducerWithRank<T>> implements Evaluable<T>, Compactable {
	protected double e;
	protected boolean tolerateNull;

	public static final KernelizedEvaluable<Pair> highestRank;

	static {
		highestRank = new AcceleratedProducer<>(
				"highestRank",
				true,
				Pair.empty(),
				PassThroughEvaluable.of(Scalar.class, 0),
				PassThroughEvaluable.of(Pair.class, 1));
	}

	public RankedChoiceProducer(double e) { this(e, true); }

	public RankedChoiceProducer(double e, boolean tolerateNull) { this.e = e; this.tolerateNull = tolerateNull; }

	public double getEpsilon() { return e; }

	@Override
	public T evaluate(Object[] args) {
		Producer<T> best = null;
		double rank = Double.MAX_VALUE;

		boolean printLog = false; // Math.random() < 0.04;

		if (printLog) {
			System.out.println("RankedChoiceProducer: There are " + size() + " Producers to choose from");
		}

		r: for (ProducerWithRank<T> p : this) {
			Scalar rs = p.getRank().get().evaluate(args);
			if (rs == null) continue r;

			double r = rs.getValue();
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

	@Override
	public void compact() { forEach(ProducerWithRank::compact); }
}
