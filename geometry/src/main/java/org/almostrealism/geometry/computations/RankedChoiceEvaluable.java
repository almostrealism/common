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

package org.almostrealism.geometry.computations;

import io.almostrealism.code.OperationAdapter;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.AcceleratedEvaluable;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;
import io.almostrealism.relation.ProducerWithRank;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Compactable;

import java.util.ArrayList;

public class RankedChoiceEvaluable<T> extends ArrayList<ProducerWithRank<T, Scalar>> implements Evaluable<T>, Compactable {
	protected double e;
	protected boolean tolerateNull;

	public static final KernelizedEvaluable<Pair<?>> highestRank;

	static {
		highestRank = new AcceleratedEvaluable<>(
				"highestRank",
				true,
				Pair.empty(),
				Input.value(Scalar.shape(), 0),
				Input.value(Pair.shape(), 1));
		((OperationAdapter) highestRank).compile();
	}

	public RankedChoiceEvaluable(double e) { this(e, true); }

	public RankedChoiceEvaluable(double e, boolean tolerateNull) { this.e = e; this.tolerateNull = tolerateNull; }

	public double getEpsilon() { return e; }

	@Override
	public T evaluate(Object[] args) {
		Producer<T> best = null;
		double rank = Double.MAX_VALUE;

		boolean printLog = false; // Math.random() < 0.04;

		if (printLog) {
			System.out.println("RankedChoiceProducer: There are " + size() + " Producers to choose from");
		}

		r: for (ProducerWithRank<T, Scalar> p : this) {
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
