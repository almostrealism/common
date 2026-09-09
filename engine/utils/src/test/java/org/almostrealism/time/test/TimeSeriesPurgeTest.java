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

	/**
	 * An empty series has nothing below any cutoff. {@code purge} must be a no-op:
	 * it must not throw, and the series must remain empty afterward.
	 */
	@Test(timeout = 10000)
	public void purgeOnEmptySeriesIsNoOp() {
		TimeSeries series = new TimeSeries();

		series.purge(2.0);

		Assert.assertNull("an empty series has no value at any timestamp",
				series.valueAt(1.0));
	}

	/**
	 * A cutoff strictly before every entry's timestamp must remove nothing: the
	 * boundary loop in {@code purge} counts entries with {@code time < cutoff}, and
	 * with no entry satisfying that condition {@code toRemove} must stay at its
	 * initial value of zero rather than underflowing to {@code -1} (which, prior to
	 * the fix, could remove entries via the {@code -1 < i} loop condition on the
	 * removal side under different entry counts).
	 */
	@Test(timeout = 10000)
	public void purgeRemovesNothingWhenCutoffPrecedesAllEntries() {
		TimeSeries series = new TimeSeries();
		series.add(new TemporalScalar(1.0, 2.0));
		series.add(new TemporalScalar(2.0, 3.0));

		series.purge(0.0);

		TemporalScalar interpolated = series.valueAt(1.5);
		Assert.assertNotNull("both entries must survive a cutoff before either of them",
				interpolated);
		Assert.assertEquals(2.5, interpolated.getValue(), 1e-9);
	}

	/**
	 * A cutoff strictly after every entry's timestamp must remove all of them,
	 * leaving the series empty.
	 */
	@Test(timeout = 10000)
	public void purgeRemovesEverythingWhenCutoffFollowsAllEntries() {
		TimeSeries series = new TimeSeries();
		series.add(new TemporalScalar(0.0, 1.0));
		series.add(new TemporalScalar(1.0, 2.0));

		series.purge(5.0);

		Assert.assertNull("all entries are below the cutoff and must be purged",
				series.valueAt(0.5));
	}

	/**
	 * The cutoff comparison in {@code purge} is {@code time < cutoff}, so an entry
	 * whose timestamp exactly equals the cutoff is not "strictly less than" it and
	 * must be retained, matching the documented contract ("all entries with
	 * time < this value are removed").
	 *
	 * <p>This is checked by interpolating between the cutoff entry and a later entry
	 * rather than by querying {@code valueAt(cutoff)} directly: {@link TimeSeries#valueAt}
	 * only interpolates between a point strictly before the query time and one at or
	 * after it, so a query exactly at the leftmost surviving entry has no predecessor
	 * to interpolate from and returns {@code null} independent of whether {@code purge}
	 * behaved correctly. Interpolating past the cutoff entry instead distinguishes the
	 * two outcomes: if the cutoff entry were wrongly purged, only the later entry would
	 * remain and the query would return {@code null}; since it is retained, the query
	 * resolves to a definite interpolated value.</p>
	 */
	@Test(timeout = 10000)
	public void purgeRetainsEntryExactlyAtCutoff() {
		TimeSeries series = new TimeSeries();
		series.add(new TemporalScalar(1.0, 2.0));
		series.add(new TemporalScalar(2.0, 3.0));
		series.add(new TemporalScalar(4.0, 5.0));

		series.purge(2.0);

		TemporalScalar interpolated = series.valueAt(3.0);
		Assert.assertNotNull("the entry at the cutoff itself must be retained so that "
				+ "interpolation past it succeeds", interpolated);
		Assert.assertEquals(4.0, interpolated.getValue(), 1e-9);
	}
}
