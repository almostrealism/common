/*
 * Copyright 2018 Michael Murray
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
	@Override
	public T evaluate(Object[] args) {
		Producer<T> best = null;
		double rank = Double.MAX_VALUE;

		r: for (ProducerWithRank<T> p : this) {
			double r = p.getRank().evaluate(args).getValue();
			if (r < 0) continue r;

			if (best == null) {
				best = p.getProducer();
			} else {
				if (r >= 0 && r < rank) {
					best = p.getProducer();
					rank = r;
				}
			}

			if (rank == 0) break r;
		}

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
