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

package org.almostrealism.music.data.test;

import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Tests for parameter function power calculations.
 */
public class ParameterFunctionTest extends TestSuiteBase {

	/**
	 * Test parameter function power computation.
	 */
	@Test(timeout = 10_000)
	public void power() {
		ParameterFunction f = ParameterFunction.random();
		IntStream.range(0, 10).mapToObj(i -> ParameterSet.random()).mapToDouble(f.power(2.0, 3, -3)::apply).forEach(System.out::println);
	}

	/**
	 * Reseeding with the same value reproduces the same sequence of random functions,
	 * and a different seed produces a different sequence.
	 */
	@Test(timeout = 10_000)
	public void randomSeedReproducesDraws() {
		ParameterSet params = new ParameterSet(0.3, 0.6, 0.9);

		ParameterFunction.setRandomSeed(7);
		double[] first = IntStream.range(0, 8).mapToDouble(i -> ParameterFunction.random().apply(params)).toArray();
		ParameterFunction.setRandomSeed(7);
		double[] second = IntStream.range(0, 8).mapToDouble(i -> ParameterFunction.random().apply(params)).toArray();
		ParameterFunction.setRandomSeed(8);
		double[] other = IntStream.range(0, 8).mapToDouble(i -> ParameterFunction.random().apply(params)).toArray();

		Assert.assertArrayEquals(first, second, 0.0);
		Assert.assertFalse(Arrays.equals(first, other));
	}
}
