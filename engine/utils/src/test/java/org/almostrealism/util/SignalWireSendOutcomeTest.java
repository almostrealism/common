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

package org.almostrealism.util;

import org.almostrealism.util.SignalWireDeliveryProvider.SendOutcome;
import org.junit.Assert;
import org.junit.Test;

/**
 * Locks the reporting contract of {@link SendOutcome}.
 *
 * <p>These tests exist because a send that the API accepted was previously
 * logged as delivered, which is a stronger claim than the response supports:
 * an accepted message can still fail on the way to the handset. An operator
 * reading the log needs the difference, and the message identifier that lets
 * them look the delivery up where it is actually recorded.</p>
 */
public class SignalWireSendOutcomeTest {

	/** A created or ok status is acceptance. */
	@Test(timeout = 10000)
	public void testAcceptedStatuses() {
		Assert.assertTrue(new SendOutcome(200, "{}").isAccepted());
		Assert.assertTrue(new SendOutcome(201, "{}").isAccepted());
	}

	/** Anything else is not, including the redirect and error ranges. */
	@Test(timeout = 10000)
	public void testRejectedStatuses() {
		Assert.assertFalse(new SendOutcome(202, "{}").isAccepted());
		Assert.assertFalse(new SendOutcome(302, "{}").isAccepted());
		Assert.assertFalse(new SendOutcome(401, "{}").isAccepted());
		Assert.assertFalse(new SendOutcome(422, "{}").isAccepted());
		Assert.assertFalse(new SendOutcome(500, "{}").isAccepted());
	}

	/** A null body is reported as empty so a log line never prints "null". */
	@Test(timeout = 10000)
	public void testNullBodyBecomesEmpty() {
		Assert.assertEquals("", new SendOutcome(201, null).getBody());
		Assert.assertEquals("", new SendOutcome(201, null).summary());
	}

	/** A body short enough to log is returned whole. */
	@Test(timeout = 10000)
	public void testShortBodyIsNotShortened() {
		String body = "{\"sid\":\"SM123\",\"status\":\"queued\"}";
		Assert.assertEquals(body, new SendOutcome(201, body).summary());
	}

	/**
	 * A long body is shortened, but keeps its leading characters — the
	 * identifier and status sit at the front, and they are the reason the
	 * body is logged at all.
	 */
	@Test(timeout = 10000)
	public void testLongBodyIsShortenedButKeepsTheIdentifier() {
		StringBuilder body = new StringBuilder("{\"sid\":\"SM123\",\"status\":\"queued\",\"x\":\"");
		for (int i = 0; i < 500; i++) {
			body.append("y");
		}
		body.append("\"}");

		String summary = new SendOutcome(201, body.toString()).summary();

		Assert.assertTrue(summary.length() < body.length());
		Assert.assertTrue(summary.endsWith("..."));
		Assert.assertTrue("the identifier must survive shortening",
				summary.contains("SM123"));
		Assert.assertTrue("the status must survive shortening",
				summary.contains("queued"));
	}
}
