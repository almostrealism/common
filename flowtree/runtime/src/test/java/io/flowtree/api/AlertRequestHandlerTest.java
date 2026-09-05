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

package io.flowtree.api;

import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.workstream.AlertRecipientEntry;
import org.almostrealism.io.Alert;
import org.almostrealism.io.AlertRecipients;
import org.almostrealism.io.RateLimit;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AlertRequestHandler}, the {@code POST /api/alerts} route
 * that lets a caller send free-form text to named recipients.
 */
public class AlertRequestHandlerTest extends TestSuiteBase {

	/** Alerts delivered to the recipient registered as {@code michael}. */
	private final List<Alert> delivered = new ArrayList<>();

	/**
	 * Returns a handler with {@code michael} registered and a generous
	 * per-caller budget.
	 *
	 * @param body the request body the handler will read
	 * @return the handler under test
	 */
	protected AlertRequestHandler handler(String body) {
		return handler(body, new RateLimit(10, Duration.ofHours(1)));
	}

	/**
	 * Returns a handler with {@code michael} registered and the given budget.
	 *
	 * @param body  the request body the handler will read
	 * @param limit the per-caller budget to enforce
	 * @return the handler under test
	 */
	protected AlertRequestHandler handler(String body, RateLimit limit) {
		AlertRecipients recipients = new AlertRecipients().add("michael", delivered::add);
		return new AlertRequestHandler(recipients, limit, session -> body);
	}

	/**
	 * Reads a response body back as a string.
	 *
	 * @param response the response to read
	 * @return the response body
	 */
	protected String bodyOf(Response response) {
		try {
			return new String(response.getData().readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** A well-formed request delivers the text and reports the recipient. */
	@Test(timeout = 10000)
	public void testAlertIsDeliveredToNamedRecipient() {
		Response response = handler(
				"{\"text\":\"Build is green\",\"recipients\":[\"michael\"]}").handle(null);

		assertEquals(Response.Status.OK, response.getStatus());
		assertEquals(1, delivered.size());
		assertEquals("Build is green", delivered.get(0).getMessage());
		assertEquals(Alert.Severity.INFO, delivered.get(0).getSeverity());

		String body = bodyOf(response);
		assertTrue(body.contains("\"delivered\":[\"michael\"]"));
		assertTrue(body.contains("\"unknown\":[]"));
	}

	/** Severity is honored when recognized. */
	@Test(timeout = 10000)
	public void testSeverityIsHonored() {
		handler("{\"text\":\"Disk full\",\"recipients\":[\"michael\"],"
				+ "\"severity\":\"ERROR\"}").handle(null);

		assertEquals(Alert.Severity.ERROR, delivered.get(0).getSeverity());
	}

	/** An unrecognized severity falls back to INFO rather than failing. */
	@Test(timeout = 10000)
	public void testUnknownSeverityFallsBackToInfo() {
		handler("{\"text\":\"Note\",\"recipients\":[\"michael\"],"
				+ "\"severity\":\"CATASTROPHE\"}").handle(null);

		assertEquals(Alert.Severity.INFO, delivered.get(0).getSeverity());
	}

	/** A stale handle is reported while the reachable recipients still receive. */
	@Test(timeout = 10000)
	public void testUnknownRecipientIsReported() {
		Response response = handler(
				"{\"text\":\"Note\",\"recipients\":[\"michael\",\"nobody\"]}").handle(null);

		assertEquals(Response.Status.OK, response.getStatus());
		assertEquals("the reachable recipient is still alerted", 1, delivered.size());

		String body = bodyOf(response);
		assertTrue(body.contains("\"unknown\":[\"nobody\"]"));
		assertTrue(body.contains("\"delivered\":[\"michael\"]"));
	}

	/** Missing text is rejected before anything is delivered. */
	@Test(timeout = 10000)
	public void testMissingTextIsRejected() {
		Response response = handler("{\"recipients\":[\"michael\"]}").handle(null);

		assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
		assertTrue(delivered.isEmpty());
	}

	/** Missing recipients is rejected before anything is delivered. */
	@Test(timeout = 10000)
	public void testMissingRecipientsIsRejected() {
		Response response = handler("{\"text\":\"Note\"}").handle(null);

		assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
		assertTrue(delivered.isEmpty());
	}

	/** Text beyond the accepted length is rejected rather than truncated. */
	@Test(timeout = 10000)
	public void testOverlongTextIsRejected() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 1100; i++) {
			text.append("x");
		}

		Response response = handler("{\"text\":\"" + text + "\",\"recipients\":[\"michael\"]}")
				.handle(null);

		assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
		assertTrue(delivered.isEmpty());
	}

	/** A caller over its budget is refused, and the alert is not delivered. */
	@Test(timeout = 10000)
	public void testPerCallerBudgetIsEnforced() {
		String body = "{\"text\":\"Note\",\"recipients\":[\"michael\"],"
				+ "\"caller\":\"tmp:ws/job\"}";
		RateLimit limit = new RateLimit(1, Duration.ofHours(1));

		assertEquals(Response.Status.OK, handler(body, limit).handle(null).getStatus());
		assertEquals(Response.Status.TOO_MANY_REQUESTS,
				handler(body, limit).handle(null).getStatus());
		assertEquals("the refused alert must not be delivered", 1, delivered.size());
	}

	/** One caller exhausting its budget does not refuse a different caller. */
	@Test(timeout = 10000)
	public void testBudgetIsPerCaller() {
		RateLimit limit = new RateLimit(1, Duration.ofHours(1));

		handler("{\"text\":\"Note\",\"recipients\":[\"michael\"],\"caller\":\"one\"}", limit)
				.handle(null);
		Response other = handler(
				"{\"text\":\"Note\",\"recipients\":[\"michael\"],\"caller\":\"two\"}", limit)
				.handle(null);

		assertEquals(Response.Status.OK, other.getStatus());
		assertEquals(2, delivered.size());
	}

	/** With no recipients configured the request fails with a clear message. */
	@Test(timeout = 10000)
	public void testEmptyDirectoryIsReported() {
		AlertRequestHandler handler = new AlertRequestHandler(new AlertRecipients(),
				new RateLimit(10, Duration.ofHours(1)),
				session -> "{\"text\":\"Note\",\"recipients\":[\"michael\"]}");

		Response response = handler.handle(null);
		assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
		assertTrue(bodyOf(response).contains("no alert recipients are configured"));
	}

	/** A configured entry with no number yields no recipient, and no failure. */
	@Test(timeout = 10000)
	public void testEntryWithoutNumberIsSkipped() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setName("michael");

		AlertRecipients recipients = AlertRecipientEntry.directory(
				Collections.singletonList(entry));

		assertEquals(0, recipients.size());
		assertFalse(recipients.contains("michael"));
	}

	/** A null entry list yields an empty directory rather than throwing. */
	@Test(timeout = 10000)
	public void testNullEntryListYieldsEmptyDirectory() {
		assertEquals(0, AlertRecipientEntry.directory(null).size());
	}

	/**
	 * A null element is skipped rather than thrown on. A YAML list with a
	 * blank item parses to one, and a typo in the config file should not
	 * stop the controller from starting.
	 */
	@Test(timeout = 10000)
	public void testNullEntryIsSkipped() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setName("michael");

		assertEquals(0, AlertRecipientEntry.directory(
				Arrays.asList(null, entry, null)).size());
	}

	/** Every configured entry is skipped when no SignalWire account is loaded. */
	@Test(timeout = 10000)
	public void testEntriesAreSkippedWithoutAnAccount() {
		AlertRecipientEntry entry = new AlertRecipientEntry();
		entry.setName("michael");
		entry.setSmsNumber("+15551234567");

		AlertRecipientEntry other = new AlertRecipientEntry();
		other.setName("mmurray");
		other.setSmsNumber("+15559876543");

		// No signalwire.properties is present under test, so deliveryProvider()
		// yields null for every entry and the directory comes back empty
		// rather than the controller failing to start.
		assertEquals(0, AlertRecipientEntry.directory(Arrays.asList(entry, other)).size());
	}
}
