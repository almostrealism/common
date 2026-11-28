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

package io.flowtree;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the Java half of the private-link defense: {@link PrivateLinkDetector}
 * detection, normalisation, redaction, and the content-scan variant, plus the
 * {@link PrivateLinkIncident} record-and-alert reporting.
 *
 * <p>Every link literal is assembled from fragments at run time so this source
 * file carries no intact private link that a scanner (or reviewer) would flag.</p>
 */
public class PrivateLinkDetectorTest extends TestSuiteBase {

    /** The claude.ai host, assembled so this source carries no intact host literal. */
    private static final String HOST = "claude" + ".ai";

    /** A fake bare session id, long enough to match the detector. */
    private static final String SESSION = "session_" + "fakefakefake1234";

    /** A fake Claude Code session URL built from fragments. */
    private static final String CODE_URL = "https://" + HOST + "/code/" + SESSION;

    /** A Claude Code session URL is detected. */
    @Test(timeout = 30000)
    public void detectsCodeSessionUrl() {
        assertTrue(PrivateLinkDetector.containsPrivateLink("done " + CODE_URL));
    }

    /** A bare session id with no surrounding URL is detected. */
    @Test(timeout = 30000)
    public void detectsBareSessionId() {
        assertTrue(PrivateLinkDetector.containsPrivateLink("see " + SESSION));
    }

    /** A Claude-Session attribution trailer is detected. */
    @Test(timeout = 30000)
    public void detectsClaudeSessionTrailer() {
        assertTrue(PrivateLinkDetector.containsPrivateLink(
                "Claude" + "-Session" + ": " + CODE_URL));
    }

    /** A Co-Authored-By line crediting Claude is detected. */
    @Test(timeout = 30000)
    public void detectsCoauthoredByClaude() {
        assertTrue(PrivateLinkDetector.containsPrivateLink(
                "Co-Authored" + "-By: Claude <noreply@" + "anthropic.com>"));
    }

    /** Ordinary prose, including the word "session", is not flagged. */
    @Test(timeout = 30000)
    public void cleanTextIsNotFlagged() {
        assertFalse(PrivateLinkDetector.containsPrivateLink(
                "Fix the matmul kernel offset bug."));
        assertFalse(PrivateLinkDetector.containsPrivateLink(
                "The training session improved accuracy."));
    }

    /** A URL-encoded link is decoded and detected. */
    @Test(timeout = 30000)
    public void seesThroughUrlEncoding() {
        assertTrue(PrivateLinkDetector.containsPrivateLink(
                "https://" + HOST + "%2Fcode%2F" + SESSION));
    }

    /** An HTML-entity-encoded link is decoded and detected. */
    @Test(timeout = 30000)
    public void seesThroughHtmlEntities() {
        assertTrue(PrivateLinkDetector.containsPrivateLink(
                "https://" + HOST + "&#47;code&#47;" + SESSION));
    }

    /** A link broken up by zero-width characters is detected. */
    @Test(timeout = 30000)
    public void seesThroughZeroWidthCharacters() {
        String zw = "​";
        assertTrue(PrivateLinkDetector.containsPrivateLink(
                "https://" + HOST + "/co" + zw + "de/" + SESSION));
    }

    /** A link split across a line break is detected. */
    @Test(timeout = 30000)
    public void seesThroughLineWrap() {
        assertTrue(PrivateLinkDetector.containsPrivateLink(
                "https://" + HOST + "/code/\n" + SESSION));
    }

    /** Redaction hides the link while keeping the surrounding text. */
    @Test(timeout = 30000)
    public void redactionHidesTheLink() {
        String redacted = PrivateLinkDetector.redact("start " + CODE_URL + " end");
        assertFalse(redacted.contains(SESSION));
        assertTrue(redacted.contains(PrivateLinkDetector.REDACTION));
        assertTrue(redacted.contains("start"));
        assertTrue(redacted.contains("end"));
    }

    /** Content scanning ignores a quoted attribution word but catches a real link. */
    @Test(timeout = 30000)
    public void scanFileContentIgnoresAttributionWordsButCatchesLinks() {
        assertTrue(PrivateLinkDetector.scanFileContent(
                "This file documents the Co-Authored" + "-By Claude policy.").isEmpty());
        assertFalse(PrivateLinkDetector.scanFileContent("link: " + CODE_URL).isEmpty());
    }

    /** The block message states the points the owner requires. */
    @Test(timeout = 30000)
    public void blockMessageStatesTheRequiredPoints() {
        String message = PrivateLinkDetector.blockMessage().toLowerCase();
        assertTrue(message.contains("harmful"));
        assertTrue(message.contains("public repositor"));
        assertTrue(message.contains("malice"));
        assertTrue(message.contains("reported"));
        assertTrue(message.contains("remove"));
    }

    /** A recorded incident is durable, queryable, redacted, and alerts the owner. */
    @Test(timeout = 30000)
    public void incidentIsRecordedRedactedAndAlerted() throws Exception {
        Path tmp = Files.createTempFile("private-link-incident", ".jsonl");
        Files.deleteIfExists(tmp);
        String previous = System.getProperty("ar.privateLinkIncidentLog");
        AtomicReference<String> alerted = new AtomicReference<>();
        System.setProperty("ar.privateLinkIncidentLog", tmp.toString());
        PrivateLinkIncident.installAlertSink(alerted::set);
        try {
            List<String> matched = PrivateLinkDetector.scan(CODE_URL);
            new PrivateLinkIncident("controller_github_proxy", "POST /repos/o/r/pulls", matched)
                    .record();

            List<PrivateLinkIncident> recent = PrivateLinkIncident.recent(10);
            assertFalse(recent.isEmpty());
            assertEquals("controller_github_proxy", recent.get(0).route());
            assertEquals("POST /repos/o/r/pulls", recent.get(0).target());

            String stored = Files.readString(tmp);
            assertFalse("the incident log must never contain the raw link",
                    stored.contains(SESSION));
            assertTrue(alerted.get() != null && alerted.get().contains("Blocked"));
            assertFalse(alerted.get().contains(SESSION));
        } finally {
            PrivateLinkIncident.installAlertSink(null);
            if (previous == null) {
                System.clearProperty("ar.privateLinkIncidentLog");
            } else {
                System.setProperty("ar.privateLinkIncidentLog", previous);
            }
            Files.deleteIfExists(tmp);
        }
    }
}
