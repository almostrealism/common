/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.node;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the exact human-readable strings produced by {@link NodeTimeFormatter#format(double)}
 * at the boundaries that matter: a sub-second duration, a sub-minute duration, exactly one
 * minute, and a duration carrying both minutes and seconds. These strings are shown verbatim
 * on the node status page, so their format is part of the contract.
 *
 * @author Michael Murray
 */
public class NodeTimeFormatterTest extends TestSuiteBase {

	/** A zero duration reports zero seconds and no minutes component. */
	@Test(timeout = 10000)
	public void formatsZeroAsSecondsOnly() {
		Assert.assertEquals("0.0 seconds (0.0)", NodeTimeFormatter.format(0.0));
	}

	/** A sub-minute duration omits the minutes component and shows fractional seconds. */
	@Test(timeout = 10000)
	public void formatsSubMinuteWithoutMinutes() {
		Assert.assertEquals("1.5 seconds (1500.0)", NodeTimeFormatter.format(1500.0));
	}

	/** Exactly one minute is the boundary where the minutes component first appears. */
	@Test(timeout = 10000)
	public void formatsExactlyOneMinute() {
		Assert.assertEquals("1 minutes and 0.0 seconds (60000.0)", NodeTimeFormatter.format(60000.0));
	}

	/** A duration past a minute reports both the whole minutes and the remaining seconds. */
	@Test(timeout = 10000)
	public void formatsMinutesAndSeconds() {
		Assert.assertEquals("1 minutes and 5.0 seconds (65000.0)", NodeTimeFormatter.format(65000.0));
	}
}
