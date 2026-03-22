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

package io.almostrealism.expression.test;

import io.almostrealism.kernel.KernelSeries;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Tests that {@link KernelSeries#periodic} calculates the period as the
 * least common multiple (LCM) of the input periods.
 */
public class KernelSeriesPeriodicTest extends TestSuiteBase {

	/** Verifies that a single period is returned unchanged. */
	@Test(timeout = 5000)
	public void singlePeriod() {
		KernelSeries series = KernelSeries.periodic(Collections.singletonList(7));
		Assert.assertTrue(series.getPeriod().isPresent());
		Assert.assertEquals(7, series.getPeriod().getAsInt());
	}

	/** Verifies that coprime periods produce an LCM equal to their product. */
	@Test(timeout = 5000)
	public void coprimePeriodsUseLCMEqualToProduct() {
		KernelSeries series = KernelSeries.periodic(Arrays.asList(3, 5));
		Assert.assertEquals(15, series.getPeriod().getAsInt());
	}

	/** Verifies that periods sharing a common factor produce the correct LCM. */
	@Test(timeout = 5000)
	public void periodsWithCommonFactorUseLCM() {
		KernelSeries series = KernelSeries.periodic(Arrays.asList(4, 6));
		Assert.assertEquals(12, series.getPeriod().getAsInt());
	}

	/** Verifies LCM calculation across three periods. */
	@Test(timeout = 5000)
	public void threePeriodsUseLCM() {
		KernelSeries series = KernelSeries.periodic(Arrays.asList(2, 3, 4));
		Assert.assertEquals(12, series.getPeriod().getAsInt());
	}

	/** Verifies that duplicate periods are ignored in the LCM calculation. */
	@Test(timeout = 5000)
	public void duplicatePeriodsIgnored() {
		KernelSeries series = KernelSeries.periodic(Arrays.asList(6, 6, 4));
		Assert.assertEquals(12, series.getPeriod().getAsInt());
	}

	/** Verifies that identical periods return the same period. */
	@Test(timeout = 5000)
	public void identicalPeriodsReturnSamePeriod() {
		KernelSeries series = KernelSeries.periodic(Arrays.asList(5, 5));
		Assert.assertEquals(5, series.getPeriod().getAsInt());
	}
}
