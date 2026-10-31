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

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import org.almostrealism.io.Alert;
import org.almostrealism.io.AlertRecipients;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AlertEndpoints}, the SMS alerting surface that owns the
 * outbound {@code POST /api/alerts} handler and the inbound SignalWire webhook
 * and rebuilds them as recipients and opt-out state change.
 */
public class AlertEndpointsTest extends TestSuiteBase {

	/** Alerts delivered to the recipient registered as {@code michael}. */
	private final List<Alert> delivered = new ArrayList<>();

	/**
	 * Returns a body reader that always yields the given request body,
	 * regardless of session, matching how the handlers read a POST body.
	 *
	 * @param body the body to return
	 * @return a body reader yielding {@code body}
	 */
	private static Function<IHTTPSession, String> bodyReader(String body) {
		return session -> body;
	}

	/**
	 * Returns a directory with {@code michael} registered to collect alerts.
	 *
	 * @return the recipient directory
	 */
	private AlertRecipients michael() {
		return new AlertRecipients().add("michael", delivered::add);
	}

	/**
	 * Reads a response body back as a string.
	 *
	 * @param response the response to read
	 * @return the response body
	 */
	private static String bodyOf(Response response) {
		try {
			return new String(response.getData().readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Before any opt-out record is configured the inbound webhook reports that
	 * it is not configured rather than serving a reply.
	 */
	@Test(timeout = 10000)
	public void testInboundUnconfiguredReports503() {
		AlertEndpoints endpoints = new AlertEndpoints(bodyReader(""));

		Response response = endpoints.handleInbound(null);

		assertEquals(Response.Status.SERVICE_UNAVAILABLE, response.getStatus());
		assertTrue(bodyOf(response).contains("inbound SMS is not configured"));
	}

	/**
	 * Configuring an opt-out record wires the inbound webhook, which then serves
	 * a cXML reply rather than the unconfigured error.
	 */
	@Test(timeout = 10000)
	public void testComplianceWithRegistryEnablesInbound() {
		AlertEndpoints endpoints = new AlertEndpoints(bodyReader(""));
		endpoints.setCompliance(Map.of(), new InMemorySmsOptOut());

		Response response = endpoints.handleInbound(null);

		assertEquals(Response.Status.OK, response.getStatus());
		assertTrue(bodyOf(response).contains("<Response"));
		assertFalse(bodyOf(response).contains("inbound SMS is not configured"));
	}

	/**
	 * Configuring compliance with a {@code null} registry leaves the inbound
	 * webhook unconfigured, so it still reports 503.
	 */
	@Test(timeout = 10000)
	public void testComplianceWithNullRegistryLeavesInboundUnconfigured() {
		AlertEndpoints endpoints = new AlertEndpoints(bodyReader(""));
		endpoints.setCompliance(null, null);

		Response response = endpoints.handleInbound(null);

		assertEquals(Response.Status.SERVICE_UNAVAILABLE, response.getStatus());
		assertTrue(bodyOf(response).contains("inbound SMS is not configured"));
	}

	/**
	 * With recipients set, the alert route delivers to a named recipient and
	 * reports it, proving the handler was rebuilt against the new directory.
	 */
	@Test(timeout = 10000)
	public void testSetRecipientsMakesRecipientReachable() {
		AlertEndpoints endpoints = new AlertEndpoints(
				bodyReader("{\"text\":\"Build is green\",\"recipients\":[\"michael\"]}"));
		endpoints.setRecipients(michael());

		Response response = endpoints.handleAlert(null);

		assertEquals(Response.Status.OK, response.getStatus());
		assertEquals(1, delivered.size());
		assertEquals("Build is green", delivered.get(0).getMessage());
		assertTrue(bodyOf(response).contains("\"delivered\":[\"michael\"]"));
	}

	/**
	 * An opted-out recipient is skipped and reported through the alert route,
	 * proving {@link AlertEndpoints#setCompliance} rebuilds the alert handler
	 * with the number map and opt-out record.
	 */
	@Test(timeout = 10000)
	public void testComplianceIsAppliedToOutboundAlerts() {
		InMemorySmsOptOut registry = new InMemorySmsOptOut();
		registry.optOut("+18335551234");

		AlertEndpoints endpoints = new AlertEndpoints(
				bodyReader("{\"text\":\"Note\",\"recipients\":[\"michael\"]}"));
		endpoints.setRecipients(michael());
		endpoints.setCompliance(Map.of("michael", "+18335551234"), registry);

		Response response = endpoints.handleAlert(null);

		assertEquals(Response.Status.OK, response.getStatus());
		assertEquals("the opted-out recipient is not delivered to", 0, delivered.size());
		String body = bodyOf(response);
		assertTrue(body.contains("\"optedOut\":[\"michael\"]"));
		assertTrue(body.contains("\"delivered\":[]"));
	}

	/**
	 * Before recipients are configured the alert route answers coherently that
	 * no recipients are configured rather than failing as an unknown route.
	 */
	@Test(timeout = 10000)
	public void testAlertBeforeRecipientsConfiguredIsCoherent() {
		AlertEndpoints endpoints = new AlertEndpoints(
				bodyReader("{\"text\":\"Note\",\"recipients\":[\"michael\"]}"));

		Response response = endpoints.handleAlert(null);

		assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
		assertTrue(bodyOf(response).contains("no alert recipients are configured"));
	}
}
