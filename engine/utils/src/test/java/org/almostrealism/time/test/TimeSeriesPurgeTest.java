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

package org.almostrealism.time.test;

import org.almostrealism.time.TemporalScalar;
import org.almostrealism.time.TimeSeries;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link TimeSeries#purge(double)}.
 *
 * <p>{@link TimeSeries#purge(double)} is documented to remove every entry whose
 * timestamp is strictly less than the cutoff. Its own javadoc example states that
 * for entries at times {@code 0.0}, {@code 1.0} and {@code 2.0}, calling
 * {@code purge(2.0)} "Removes entries at 0.0 and 1.0" so that "Only entry at 2.0
 * remains". These tests pin that contract by observing, through
 * {@link TimeSeries#valueAt(double)}, that no entry below the cutoff survives.</p>
 */
public class TimeSeriesPurgeTest extends TestSuiteBase {

	/**
	 * The documented three-entry example: after {@code purge(2.0)} only the entry at
	 * {@code 2.0} may remain. With only a single surviving point, linear interpolation
	 * at {@code 1.5} is impossible, so {@link TimeSeries#valueAt(double)} must return
	 * {@code null}. If the entry at {@code 1.0} (which is below the cutoff) is wrongly
	 * retained, {@code valueAt(1.5)} interpolates between {@code 1.0} and {@code 2.0}
	 * and returns a non-null value.
	 */
	@Test(timeout = 10000)
	public void purgeRemovesEveryEntryBelowCutoff() {
		TimeSeries series = new TimeSeries();
		series.add(new TemporalScalar(0.0, 1.0));
		series.add(new TemporalScalar(1.0, 2.0));
		series.add(new TemporalScalar(2.0, 3.0));

		series.purge(2.0);

		Assert.assertNull("entry at 1.0 is below the cutoff and must be purged",
				series.valueAt(1.5));
	}

	/**
	 * The documented four-entry example: for entries at times {@code 0.0}, {@code 1.0},
	 * {@code 2.0} and {@code 3.0}, {@code purge(2.0)} must leave only the entries at
	 * {@code 2.0} and {@code 3.0}. The surviving pair must still interpolate
	 * ({@code valueAt(2.5) == 3.5}), while no point at or below {@code 1.0} may remain
	 * ({@code valueAt(1.5) == null}).
	 */
	@Test(timeout = 10000)
	public void purgeMatchesDocumentedFourEntryExample() {
		TimeSeries series = new TimeSeries();
		series.add(new TemporalScalar(0.0, 1.0));
		series.add(new TemporalScalar(1.0, 2.0));
		series.add(new TemporalScalar(2.0, 3.0));
		series.add(new TemporalScalar(3.0, 4.0));

		series.purge(2.0);

		TemporalScalar interpolated = series.valueAt(2.5);
		Assert.assertNotNull("surviving entries at 2.0 and 3.0 must interpolate", interpolated);
		Assert.assertEquals(3.5, interpolated.getValue(), 1e-9);

		Assert.assertNull("entry at 1.0 is below the cutoff and must be purged",
				series.valueAt(1.5));
	}
}
