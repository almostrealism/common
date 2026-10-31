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

import org.almostrealism.util.SmsComplianceProgram;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link InboundSmsHandler}, the SignalWire inbound-message webhook
 * that applies the registered STOP/START/HELP keyword rules.
 */
public class InboundSmsHandlerTest extends TestSuiteBase {

	/** The sender number used across the keyword tests. */
	private static final String SENDER = "+18335551234";

	/** STOP records an opt-out and replies with the unsubscribe confirmation. */
	@Test(timeout = 10000)
	public void testStopOptsOutAndConfirms() {
		InMemorySmsOptOut registry = new InMemorySmsOptOut();
		String xml = new InboundSmsHandler(registry).respond(SENDER, "STOP");

		assertTrue("the sender is now opted out", registry.isOptedOut(SENDER));
		assertTrue(xml.contains("<Message>"));
		assertTrue(xml.contains("You have been unsubscribed"));
	}

	/** START clears an opt-out and replies with the resubscribe confirmation. */
	@Test(timeout = 10000)
	public void testStartOptsInAndConfirms() {
		InMemorySmsOptOut registry = new InMemorySmsOptOut();
		registry.optOut(SENDER);

		String xml = new InboundSmsHandler(registry).respond(SENDER, "start");

		assertFalse("the sender is resubscribed", registry.isOptedOut(SENDER));
		assertTrue(xml.contains("resubscribed to work updates"));
		// The reply contains "Msg&data", which must be XML-escaped in cXML.
		assertTrue(xml.contains("Msg&amp;data"));
		assertFalse(xml.contains("Msg&data"));
	}

	/** HELP replies with program info and changes no opt-out state. */
	@Test(timeout = 10000)
	public void testHelpRepliesWithoutChangingState() {
		InMemorySmsOptOut registry = new InMemorySmsOptOut();
		String xml = new InboundSmsHandler(registry).respond(SENDER, "HELP");

		assertFalse(registry.isOptedOut(SENDER));
		assertTrue(xml.contains("Automated work updates"));
		assertTrue(xml.contains("Msg&amp;data"));
	}

	/** An unrecognized body yields the empty response and no reply. */
	@Test(timeout = 10000)
	public void testUnrecognizedBodyYieldsEmptyResponse() {
		InMemorySmsOptOut registry = new InMemorySmsOptOut();
		String xml = new InboundSmsHandler(registry).respond(SENDER, "what time is it?");

		assertFalse(registry.isOptedOut(SENDER));
		assertTrue(xml.contains("<Response/>"));
		assertFalse(xml.contains("<Message>"));
	}

	/** The reply text is drawn from the registered program, not reinvented. */
	@Test(timeout = 10000)
	public void testReplyTextMatchesProgram() {
		assertEquals(SmsComplianceProgram.STOP_REPLY,
				new InboundSmsHandler(new InMemorySmsOptOut()).reply(SENDER, "STOP"));
	}
}
