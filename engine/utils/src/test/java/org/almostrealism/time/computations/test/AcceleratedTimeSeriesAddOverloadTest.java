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

package org.almostrealism.time.computations.test;

import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.TemporalScalar;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for the deprecated {@link AcceleratedTimeSeries#add(double, double)}
 * overload, which must write data into the same slots that
 * {@link AcceleratedTimeSeries#add(TemporalScalar)} uses so that
 * {@link AcceleratedTimeSeries#valueAt(double)} can read it back.
 */
public class AcceleratedTimeSeriesAddOverloadTest extends TestSuiteBase {

	/**
	 * Adding two points through the {@code add(double, double)} overload must produce
	 * a series that interpolates identically to one built with {@code add(TemporalScalar)}.
	 *
	 * <p>The overload wrote its entry one slot past the valid cursor range, leaving the
	 * first live slot as unwritten zeros and orphaning the real data beyond the end
	 * cursor. With that defect present, {@code valueAt(1.5)} finds no point at or after
	 * time 1.5 and returns {@code null}.</p>
	 */
	@Test(timeout = 10000)
	public void addDoubleOverloadInterpolates() {
		AcceleratedTimeSeries series = new AcceleratedTimeSeries(100);
		series.add(1.0, 10.0);
		series.add(2.0, 20.0);

		Assert.assertEquals(2, series.getLength());

		TemporalScalar interpolated = series.valueAt(1.5);
		Assert.assertNotNull("valueAt(1.5) returned null; the overload wrote to the wrong slot",
				interpolated);
		Assert.assertEquals(15.0, interpolated.getValue(), Math.pow(10, -10));
	}

	/**
	 * The {@code add(double, double)} overload must place data in the same slots as
	 * {@code add(TemporalScalar)}, so a series built with each overload reads back
	 * identically.
	 */
	@Test(timeout = 10000)
	public void addDoubleOverloadMatchesTemporalScalar() {
		AcceleratedTimeSeries viaDoubles = new AcceleratedTimeSeries(100);
		viaDoubles.add(1.0, 10.0);
		viaDoubles.add(3.0, 30.0);

		AcceleratedTimeSeries viaScalar = new AcceleratedTimeSeries(100);
		viaScalar.add(new TemporalScalar(1.0, 10.0));
		viaScalar.add(new TemporalScalar(3.0, 30.0));

		Assert.assertEquals(viaScalar.getLength(), viaDoubles.getLength());
		Assert.assertEquals(viaScalar.valueAt(2.0).getValue(),
				viaDoubles.valueAt(2.0).getValue(), Math.pow(10, -10));
	}
}
