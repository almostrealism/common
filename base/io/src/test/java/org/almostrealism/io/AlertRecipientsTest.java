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

package org.almostrealism.io;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Locks the behavior of {@link AlertRecipients}, the targeted counterpart to
 * the broadcast delivery {@link Console#alert(Alert)} performs.
 */
public class AlertRecipientsTest {

	/** An alert sent to a known handle reaches that handle's provider. */
	@Test(timeout = 10000)
	public void testAlertReachesNamedRecipient() {
		List<Alert> delivered = new ArrayList<>();
		AlertRecipients recipients = new AlertRecipients().add("michael", delivered::add);

		List<String> unknown = recipients.send(
				new Alert(Alert.Severity.INFO, "hello"),
				Collections.singletonList("michael"));

		Assert.assertTrue(unknown.isEmpty());
		Assert.assertEquals(1, delivered.size());
		Assert.assertEquals("hello", delivered.get(0).getMessage());
	}

	/** A stale handle is reported without blocking the recipients that resolve. */
	@Test(timeout = 10000)
	public void testUnknownRecipientIsReportedNotThrown() {
		List<Alert> delivered = new ArrayList<>();
		AlertRecipients recipients = new AlertRecipients().add("michael", delivered::add);

		List<String> unknown = recipients.send(
				new Alert(Alert.Severity.INFO, "hello"),
				Arrays.asList("michael", "nobody"));

		Assert.assertEquals(Collections.singletonList("nobody"), unknown);
		Assert.assertEquals("the known recipient must still be reached",
				1, delivered.size());
	}

	/** A provider that throws does not prevent the remaining deliveries. */
	@Test(timeout = 10000)
	public void testThrowingProviderDoesNotSuppressOthers() {
		List<Alert> delivered = new ArrayList<>();
		AlertRecipients recipients = new AlertRecipients()
				.add("broken", alert -> { throw new IllegalStateException("boom"); })
				.add("michael", delivered::add);

		List<String> unknown = recipients.send(
				new Alert(Alert.Severity.ERROR, "hello"),
				Arrays.asList("broken", "michael"));

		Assert.assertTrue("a failing provider resolved, so it is not unknown",
				unknown.isEmpty());
		Assert.assertEquals(1, delivered.size());
	}

	/** Registration ignores blank names and null providers rather than storing them. */
	@Test(timeout = 10000)
	public void testBlankRegistrationsAreIgnored() {
		AlertRecipients recipients = new AlertRecipients()
				.add("", alert -> { })
				.add(null, alert -> { })
				.add("michael", null);

		Assert.assertEquals(0, recipients.size());
		Assert.assertFalse(recipients.contains("michael"));
	}

	/** Handles are matched after trimming, on both registration and lookup. */
	@Test(timeout = 10000)
	public void testHandlesAreTrimmed() {
		AlertRecipients recipients = new AlertRecipients().add("  michael  ", alert -> { });

		Assert.assertTrue(recipients.contains("michael"));
		Assert.assertTrue(recipients.contains(" michael"));
		Assert.assertEquals(Collections.singletonList("michael"), recipients.names());
	}

	/** Registering a name twice replaces the provider rather than duplicating it. */
	@Test(timeout = 10000)
	public void testReregistrationReplaces() {
		List<Alert> first = new ArrayList<>();
		List<Alert> second = new ArrayList<>();

		AlertRecipients recipients = new AlertRecipients()
				.add("michael", first::add)
				.add("michael", second::add);

		recipients.send(new Alert(Alert.Severity.INFO, "hello"),
				Collections.singletonList("michael"));

		Assert.assertEquals(1, recipients.size());
		Assert.assertTrue(first.isEmpty());
		Assert.assertEquals(1, second.size());
	}

	/** Null arguments yield no delivery and no unknown names. */
	@Test(timeout = 10000)
	public void testNullArgumentsAreInert() {
		AlertRecipients recipients = new AlertRecipients().add("michael", alert -> { });

		Assert.assertTrue(recipients.send(null, Collections.singletonList("michael")).isEmpty());
		Assert.assertTrue(recipients.send(new Alert(Alert.Severity.INFO, "x"), null).isEmpty());
	}
}
