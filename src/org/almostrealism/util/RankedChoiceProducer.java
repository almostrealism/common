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

import java.util.ArrayList;

public class RankedChoiceProducer<T> extends ArrayList<ProducerWithRank<T>> implements Producer<T> {
	private double e;

	public RankedChoiceProducer(double e) { this.e = e; }

	@Override
	public T evaluate(Object[] args) {
		Producer<T> best = null;
		double rank = Double.MAX_VALUE;

		boolean printLog = false; // Math.random() < 0.04;

		if (printLog) {
			System.out.println("RankedChoiceProducer: There are " + size() + " Producers to choose from");
		}

		r: for (ProducerWithRank<T> p : this) {
			double r = p.getRank().evaluate(args).getValue();
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

		return best == null ? null : best.evaluate(args);
	}

	@Override
	public void compact() {
		// TODO  Hardware acceleration for ranked choice
		for (ProducerWithRank p : this) {
			p.getProducer().compact();
			p.getRank().compact();
		}
	}
}
