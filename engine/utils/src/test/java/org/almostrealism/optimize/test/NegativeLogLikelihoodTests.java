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

package org.almostrealism.optimize.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.NegativeLogLikelihood;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the loss terms of {@link NegativeLogLikelihood} over rows as wide as a language model's
 * vocabulary.
 */
public class NegativeLogLikelihoodTests extends TestSuiteBase {
	/**
	 * {@link NegativeLogLikelihood} scores a row as wide as a large vocabulary quickly: the term
	 * of the row is computed as a reduction over its columns rather than as one expression with a
	 * term for every column, which took tens of seconds to compile for a row of this width. For an
	 * output whose log-probabilities are all equal and a one-hot target, the term is the negated
	 * log-probability.
	 */
	@Test(timeout = 10000)
	public void negativeLogLikelihoodOfWideRow() {
		int classes = 8487;
		PackedCollection output = new PackedCollection(shape(classes));
		output.fill(-9.0);
		PackedCollection target = new PackedCollection(shape(classes));
		target.setMem(5, 1.0);

		PackedCollection terms = new NegativeLogLikelihood()
				.loss(cp(output.each()), cp(target.each())).evaluate();
		Assert.assertEquals(1, terms.getMemLength());
		Assert.assertEquals(9.0, terms.toDouble(0), 1e-4);
	}
}
