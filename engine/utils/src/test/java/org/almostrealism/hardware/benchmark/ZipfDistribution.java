/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.benchmark;

import java.util.Random;

/**
 * A Zipf distribution over the indices of a finite pool: index {@code k} is drawn with
 * probability proportional to {@code 1 / (k + 1)^skew}. A skew of 0 draws uniformly; a larger
 * skew concentrates the draws on the first entries of the pool.
 */
public class ZipfDistribution {
	/** The cumulative, unnormalized probability of each index. */
	private final double[] cumulative;

	/**
	 * Creates the distribution.
	 *
	 * @param size the number of entries in the pool, at least 1
	 * @param skew the Zipf exponent
	 */
	public ZipfDistribution(int size, double skew) {
		cumulative = new double[size];
		double total = 0.0;
		for (int k = 0; k < size; k++) {
			total += 1.0 / Math.pow(k + 1, skew);
			cumulative[k] = total;
		}
	}

	/**
	 * Draws an index.
	 *
	 * @param random the stream to draw from
	 * @return an index in {@code [0, size)}
	 */
	public int sample(Random random) {
		double target = random.nextDouble() * cumulative[cumulative.length - 1];
		for (int k = 0; k < cumulative.length; k++) {
			if (target < cumulative[k]) return k;
		}

		return cumulative.length - 1;
	}
}
