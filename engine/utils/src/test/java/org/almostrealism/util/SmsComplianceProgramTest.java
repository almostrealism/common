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

import org.almostrealism.util.SmsComplianceProgram.Keyword;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link SmsComplianceProgram}, the registered message contract.
 *
 * <p>The round-trip tests use platform-neutral alert text rather than the
 * literal carrier-registration samples, which name downstream products that do
 * not belong in this public repository. Structurally the assertion is the same
 * one the registration requires: the complete body is the brand prefix, the
 * alert text verbatim, and the opt-out suffix, with nothing else added.</p>
 */
public class SmsComplianceProgramTest extends TestSuiteBase {

	/**
	 * Formatting wraps the alert text in exactly the registered prefix and
	 * suffix, leaving short text verbatim between them.
	 */
	@Test(timeout = 10000)
	public void testFormatWrapsWithPrefixAndSuffix() {
		String alertText = "Build failed for the renderer on master. See CI logs for details.";
		String expected = "Almost Realism Media: " + alertText
				+ " Reply STOP to unsubscribe, HELP for assistance.";

		Assert.assertEquals(expected, SmsComplianceProgram.formatAlert(alertText));
	}

	/**
	 * Several short bodies each round-trip to prefix + text + suffix exactly,
	 * which is the property the carrier-registered samples rely on.
	 */
	@Test(timeout = 10000)
	public void testRegisteredSamplesRoundTripExactly() {
		String[] alertTexts = {
				"Build failed for the renderer on master. See CI logs for details.",
				"Agent job completed on the compliance workstream - review requested.",
				"ALERT - the inference server is unreachable in production."
		};

		for (String alertText : alertTexts) {
			Assert.assertEquals(
					SmsComplianceProgram.PREFIX + alertText + SmsComplianceProgram.OPT_OUT_SUFFIX,
					SmsComplianceProgram.formatAlert(alertText));
		}
	}

	/** Formatting normalizes the alert text to GSM-7 as it wraps it. */
	@Test(timeout = 10000)
	public void testFormatNormalizesAlertText() {
		String body = SmsComplianceProgram.formatAlert("queued — then “done”");
		Assert.assertTrue(body.contains("queued - then \"done\""));
		Assert.assertFalse(body.contains("—"));
	}

	/**
	 * An over-long alert text is truncated so the whole message fits one GSM-7
	 * segment, while the brand prefix and opt-out suffix survive intact.
	 */
	@Test(timeout = 10000)
	public void testOverlongAlertTextIsTruncatedPreservingFraming() {
		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 50; i++) {
			longText.append("word ");
		}

		String body = SmsComplianceProgram.formatAlert(longText.toString());

		Assert.assertTrue("prefix must survive",
				body.startsWith(SmsComplianceProgram.PREFIX));
		Assert.assertTrue("opt-out suffix must survive",
				body.endsWith(SmsComplianceProgram.OPT_OUT_SUFFIX));
		Assert.assertTrue("the whole message stays within one GSM-7 segment",
				Gsm7.septetLength(body) <= SmsComplianceProgram.MAX_SEGMENT_SEPTETS);

		String middle = body.substring(SmsComplianceProgram.PREFIX.length(),
				body.length() - SmsComplianceProgram.OPT_OUT_SUFFIX.length());
		Assert.assertTrue("the truncated alert text is marked with an ellipsis",
				middle.endsWith("..."));
	}

	/** The STOP keyword set maps to the opt-out action, case-insensitively. */
	@Test(timeout = 10000)
	public void testStopKeywordsClassified() {
		Assert.assertEquals(Keyword.STOP, SmsComplianceProgram.classifyInbound("STOP"));
		Assert.assertEquals(Keyword.STOP, SmsComplianceProgram.classifyInbound(" stop "));
		Assert.assertEquals(Keyword.STOP, SmsComplianceProgram.classifyInbound("StopAll"));
		Assert.assertEquals(Keyword.STOP, SmsComplianceProgram.classifyInbound("unsubscribe"));
		Assert.assertEquals(Keyword.STOP, SmsComplianceProgram.classifyInbound("CANCEL"));
		Assert.assertEquals(Keyword.STOP, SmsComplianceProgram.classifyInbound("end"));
		Assert.assertEquals(Keyword.STOP, SmsComplianceProgram.classifyInbound("QUIT"));
	}

	/** The START keyword set maps to the resubscribe action. */
	@Test(timeout = 10000)
	public void testStartKeywordsClassified() {
		Assert.assertEquals(Keyword.START, SmsComplianceProgram.classifyInbound("START"));
		Assert.assertEquals(Keyword.START, SmsComplianceProgram.classifyInbound("unstop"));
		Assert.assertEquals(Keyword.START, SmsComplianceProgram.classifyInbound("Yes"));
	}

	/** The HELP keyword set maps to the help action. */
	@Test(timeout = 10000)
	public void testHelpKeywordsClassified() {
		Assert.assertEquals(Keyword.HELP, SmsComplianceProgram.classifyInbound("HELP"));
		Assert.assertEquals(Keyword.HELP, SmsComplianceProgram.classifyInbound("info"));
	}

	/** Anything outside the registered keyword sets requests no reply. */
	@Test(timeout = 10000)
	public void testUnrecognizedBodyIsNone() {
		Assert.assertEquals(Keyword.NONE, SmsComplianceProgram.classifyInbound("hello there"));
		Assert.assertEquals(Keyword.NONE, SmsComplianceProgram.classifyInbound(""));
		Assert.assertEquals(Keyword.NONE, SmsComplianceProgram.classifyInbound(null));
	}

	/** Each action yields its registered reply; NONE yields none. */
	@Test(timeout = 10000)
	public void testReplyForEachKeyword() {
		Assert.assertEquals(SmsComplianceProgram.STOP_REPLY,
				SmsComplianceProgram.replyFor(Keyword.STOP));
		Assert.assertEquals(SmsComplianceProgram.START_REPLY,
				SmsComplianceProgram.replyFor(Keyword.START));
		Assert.assertEquals(SmsComplianceProgram.HELP_REPLY,
				SmsComplianceProgram.replyFor(Keyword.HELP));
		Assert.assertNull(SmsComplianceProgram.replyFor(Keyword.NONE));
	}

	/** Every registered reply is itself a single GSM-7 segment. */
	@Test(timeout = 10000)
	public void testRepliesAreSingleSegment() {
		Assert.assertTrue(Gsm7.septetLength(SmsComplianceProgram.STOP_REPLY)
				<= SmsComplianceProgram.MAX_SEGMENT_SEPTETS);
		Assert.assertTrue(Gsm7.septetLength(SmsComplianceProgram.START_REPLY)
				<= SmsComplianceProgram.MAX_SEGMENT_SEPTETS);
		Assert.assertTrue(Gsm7.septetLength(SmsComplianceProgram.HELP_REPLY)
				<= SmsComplianceProgram.MAX_SEGMENT_SEPTETS);
	}
}
