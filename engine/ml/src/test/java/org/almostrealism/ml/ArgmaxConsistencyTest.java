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

package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the host-side argmax behavior shared by
 * {@link org.almostrealism.collect.CollectionFeatures#argmax(double[])},
 * {@link PackedCollection#argmax()} and the greedy branch of
 * {@link AutoregressiveModel#sampleToken(PackedCollection, int, double, double, java.util.Random)},
 * so that all three agree on the same input: the index of the largest value,
 * the first such index when the maximum is tied, and {@code -1} for an empty input.
 */
public class ArgmaxConsistencyTest extends TestSuiteBase {

	/** Number of values in {@link #distinct()} and {@link #tied()}. */
	private static final int SIZE = 5;

	/**
	 * Returns values with a single, unambiguous maximum at index 2.
	 *
	 * @return the values
	 */
	private PackedCollection distinct() {
		return pack(0.5, -1.0, 4.0, 3.5, 2.0);
	}

	/**
	 * Returns values whose maximum ({@code 3.0}) occurs at indices 1 and 3.
	 *
	 * @return the values
	 */
	private PackedCollection tied() {
		return pack(1.0, 3.0, 2.0, 3.0, -4.0);
	}

	/**
	 * All three implementations select the unique maximum.
	 */
	@Test(timeout = 30000)
	public void distinctMaximum() {
		Assert.assertEquals(2, argmax(distinct().toArray()));
		Assert.assertEquals(2, distinct().argmax());
		Assert.assertEquals(2, AutoregressiveModel.sampleToken(distinct(),
				SIZE, 0.0, 1.0, null));
	}

	/**
	 * The array form and the greedy token sampler both select the first of tied maxima.
	 */
	@Test(timeout = 30000)
	public void tiedMaximumFirstWinsForArrayAndSampler() {
		Assert.assertEquals(1, argmax(tied().toArray()));
		Assert.assertEquals(1, AutoregressiveModel.sampleToken(tied(),
				SIZE, 0.0, 1.0, null));
	}

	/**
	 * {@link PackedCollection#argmax()} selects the first of tied maxima, agreeing with
	 * the array form. It previously reduced with {@code a > b ? a : b}, which kept the
	 * last of tied maxima and returned {@code 3} here.
	 */
	@Test(timeout = 30000)
	public void tiedMaximumCollection() {
		Assert.assertEquals(1, tied().argmax());
	}

	/**
	 * {@link PackedCollection#argmax()} on a row of a traversed collection searches
	 * only that row, as {@code NegativeLogLikelihood} relies on.
	 */
	@Test(timeout = 30000)
	public void collectionRowView() {
		PackedCollection rows = pack(0.0, 9.0, 1.0, 5.0, 2.0, 7.0)
				.reshape(shape(2, 3)).traverse(1);
		Assert.assertEquals(1, rows.get(0).argmax());
		Assert.assertEquals(2, rows.get(1).argmax());
	}

	/**
	 * The greedy sampler considers only the first {@code vocabSize} logits.
	 */
	@Test(timeout = 30000)
	public void greedySamplerHonorsVocabSize() {
		Assert.assertEquals(0, AutoregressiveModel.sampleToken(distinct(),
				2, 0.0, 1.0, null));
	}

	/**
	 * Empty inputs have no maximum, for both the array form and the greedy sampler. A
	 * {@code vocabSize} of {@code 0} routes an empty {@code values} array through
	 * {@code argmax}, so the sampler agrees with the array form and yields {@code -1}
	 * rather than throwing.
	 */
	@Test(timeout = 30000)
	public void emptyInput() {
		Assert.assertEquals(-1, argmax(new double[0]));

		int emptyVocabToken = AutoregressiveModel.sampleToken(distinct(), 0, 0.0, 1.0, null);
		Assert.assertEquals(argmax(new double[0]), emptyVocabToken);
	}
}
