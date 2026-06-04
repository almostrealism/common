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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the per-job {@code sensitiveFileProtectionEnabled} flag and
 * the controller-signed {@code Sensitive-File-Bypass} commit trailer.
 *
 * <p>Covers the {@link SensitiveFileBypassTrailer} helper (signing,
 * strip-then-append message construction, agent-forgery resistance), the
 * {@link CodingAgentJob} / {@link CodingAgentJobFactory} flag defaults,
 * wire-format serialisation, factory-to-job propagation, and the
 * signature round-trip through the deserialisation path. Also pins the
 * trailer key constant to {@code "Sensitive-File-Bypass"} so a silent
 * rename is caught by the test suite rather than breaking CI.</p>
 */
public class CodingAgentJobSensitiveFileProtectionTest extends TestSuiteBase {

    // ── Trailer key constant ───────────────────────────────────────

    /**
     * Pins the trailer key string so any silent rename is caught before
     * landing. CI and the controller both rely on this exact spelling.
     */
    @Test(timeout = 30000)
    public void trailerKeyIsExactlySensitiveFileBypass() {
        assertEquals("Sensitive-File-Bypass", SensitiveFileBypassTrailer.TRAILER_KEY);
    }

    // ── Signing ───────────────────────────────────────────────────

    /** {@link SensitiveFileBypassTrailer#sign} returns null on null/empty inputs. */
    @Test(timeout = 30000)
    public void signRejectsNullOrEmpty() {
        assertNull(SensitiveFileBypassTrailer.sign(null, "job-1"));
        assertNull(SensitiveFileBypassTrailer.sign("secret", null));
        assertNull(SensitiveFileBypassTrailer.sign("", "job-1"));
        assertNull(SensitiveFileBypassTrailer.sign("secret", ""));
    }

    /**
     * {@link SensitiveFileBypassTrailer#sign} is deterministic: signing the
     * same (secret, jobId) pair always produces the same output, and
     * different secrets or job IDs produce different outputs. This is the
     * core property the verification script depends on.
     */
    @Test(timeout = 30000)
    public void signIsDeterministicAndSensitiveToInputs() {
        String a = SensitiveFileBypassTrailer.sign("s3cret", "job-A");
        String b = SensitiveFileBypassTrailer.sign("s3cret", "job-A");
        String otherJob = SensitiveFileBypassTrailer.sign("s3cret", "job-B");
        String otherSecret = SensitiveFileBypassTrailer.sign("other", "job-A");

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a, b);
        assertFalse("Different job IDs must sign differently", a.equals(otherJob));
        assertFalse("Different secrets must sign differently", a.equals(otherSecret));
    }

    /**
     * The signature is base64-URL-safe-no-padding of a 32-byte HMAC-SHA256
     * digest, which yields a 43-character ASCII string with no padding
     * and no '+' or '/' characters. The verification script reproduces
     * this same encoding, so the format must match exactly.
     */
    @Test(timeout = 30000)
    public void signProducesBase64UrlSafeNoPadding() {
        String sig = SensitiveFileBypassTrailer.sign("secret", "job-x");
        assertNotNull(sig);
        assertEquals("HMAC-SHA256 is 32 bytes; base64-URL-no-padding is 43 chars",
                SensitiveFileBypassTrailer.SIGNATURE_LENGTH, sig.length());
        assertFalse("URL-safe alphabet: no '+' allowed", sig.contains("+"));
        assertFalse("URL-safe alphabet: no '/' allowed", sig.contains("/"));
        assertFalse("No padding allowed", sig.contains("="));
        // Round-trip: the same value decodes to 32 bytes via the same alphabet.
        byte[] decoded = Base64.getUrlDecoder().decode(sig);
        assertEquals(32, decoded.length);
    }

    // ── Message stripping ─────────────────────────────────────────

    /** {@link SensitiveFileBypassTrailer#stripTrailer} removes the trailer line. */
    @Test(timeout = 30000)
    public void stripTrailerRemovesLine() {
        String input = "Title\n\nBody.\nSensitive-File-Bypass: job-1=abc\n";
        String out = SensitiveFileBypassTrailer.stripTrailer(input);
        assertFalse("Trailer line must be removed", out.contains("Sensitive-File-Bypass"));
        assertTrue("Title preserved", out.contains("Title"));
        assertTrue("Body preserved", out.contains("Body."));
    }

    /**
     * The strip step is the core of the agent-forgery defence: it must
     * remove ANY attempt by the agent to introduce a
     * {@code Sensitive-File-Bypass} trailer in commit.txt, including
     * case variants, leading whitespace, and any plausible disguise.
     * The harness then appends a single controller-signed trailer.
     */
    @Test(timeout = 30000)
    public void stripTrailerToleratesAgentDisguises() {
        String[] disguised = {
                "title\nSENSITIVE-FILE-BYPASS: job-x=forged\n",
                "title\nsensitive-file-bypass: job-x=forged\n",
                "title\n   Sensitive-File-Bypass:job-x=forged\n", // no space before colon
                "title\n\tSensitive-File-Bypass: job-x=forged\nbody\n",
        };
        for (String input : disguised) {
            String out = SensitiveFileBypassTrailer.stripTrailer(input);
            assertFalse("Strip must remove case/whitespace variants: " + input,
                    out.toLowerCase().contains("sensitive-file-bypass"));
        }
    }

    /** {@link SensitiveFileBypassTrailer#stripTrailer} tolerates null/empty. */
    @Test(timeout = 30000)
    public void stripTrailerToleratesNullAndEmpty() {
        assertEquals("", SensitiveFileBypassTrailer.stripTrailer(null));
        assertEquals("", SensitiveFileBypassTrailer.stripTrailer(""));
    }

    // ── applyToMessage: end-to-end ────────────────────────────────

    /**
     * The full strip-then-append sequence produces a message that
     * (a) does NOT contain any agent-supplied trailer and (b) contains
     * exactly one controller-signed trailer. This is the property that
     * prevents the agent from forging a bypass.
     */
    @Test(timeout = 30000)
    public void applyToMessageStripsAgentTrailerAndAppendsControllerOne() {
        String agentMessage = "title\n\n"
                + "Sensitive-File-Bypass: job-victim=forged-signature\n"
                + "SENSITIVE-FILE-BYPASS: job-other=another-forgery\n"
                + "more body\n";
        String sig = SensitiveFileBypassTrailer.sign("secret", "job-real");
        String out = SensitiveFileBypassTrailer.applyToMessage(agentMessage, "job-real", sig);

        // The forged trailer is gone; only the controller-signed one remains.
        int occurrences = countOccurrences(out.toLowerCase(), "sensitive-file-bypass");
        assertEquals("Exactly one trailer must remain after strip-then-append",
                1, occurrences);
        assertTrue("Controller-signed trailer must be present",
                out.contains("Sensitive-File-Bypass: job-real=" + sig));
        // Forged signatures must NOT appear.
        assertFalse(out.contains("forged-signature"));
        assertFalse(out.contains("another-forgery"));
    }

    /**
     * When the signature is null (e.g. controller has no signing key, or
     * the protection is enabled), the harness strips any agent-supplied
     * trailer and does NOT replace it. CI will then block the commit.
     */
    @Test(timeout = 30000)
    public void applyToMessageStripsButDoesNotAppendWhenSignatureNull() {
        String agentMessage = "title\nSensitive-File-Bypass: job-1=forged\nbody\n";
        String out = SensitiveFileBypassTrailer.applyToMessage(agentMessage, "job-1", null);
        assertFalse("Agent-supplied trailer must be stripped", out.toLowerCase()
                .contains("sensitive-file-bypass"));
    }

    /** {@link SensitiveFileBypassTrailer#applyToMessage} tolerates null/empty message. */
    @Test(timeout = 30000)
    public void applyToMessageToleratesNullMessage() {
        String sig = SensitiveFileBypassTrailer.sign("secret", "job-1");
        String out = SensitiveFileBypassTrailer.applyToMessage(null, "job-1", sig);
        assertNotNull(out);
        assertTrue(out.contains("Sensitive-File-Bypass: job-1=" + sig));
    }

    // ── CodingAgentJob flag defaults ──────────────────────────────

    /** {@link CodingAgentJob#isSensitiveFileProtectionEnabled()} defaults to true. */
    @Test(timeout = 30000)
    public void jobFlagDefaultIsTrue() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        assertTrue("Default must be TRUE (protections active by default)",
                job.isSensitiveFileProtectionEnabled());
    }

    /** {@link CodingAgentJob#setSensitiveFileProtectionEnabled(boolean)} reflects in getter. */
    @Test(timeout = 30000)
    public void jobFlagSetterRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("t1", "do something");
        job.setSensitiveFileProtectionEnabled(false);
        assertFalse(job.isSensitiveFileProtectionEnabled());
        job.setSensitiveFileProtectionEnabled(true);
        assertTrue(job.isSensitiveFileProtectionEnabled());
    }

    // ── CodingAgentJobFactory flag defaults ───────────────────────

    /** {@link CodingAgentJobFactory#isSensitiveFileProtectionEnabled()} defaults to true. */
    @Test(timeout = 30000)
    public void factoryFlagDefaultIsTrue() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertTrue("Factory default must be TRUE (protections active by default)",
                factory.isSensitiveFileProtectionEnabled());
    }

    /** Factory setSensitiveFileProtectionEnabled propagates to the job created by nextJob(). */
    @Test(timeout = 30000)
    public void factoryFlagFalsePropagatesToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        factory.setSensitiveFileProtectionEnabled(false);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertFalse("Factory flag false must propagate to job", job.isSensitiveFileProtectionEnabled());
    }

    /** Factory setSensitiveFileProtectionEnabled(true) propagates to the job. */
    @Test(timeout = 30000)
    public void factoryFlagTruePropagatesToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        factory.setSensitiveFileProtectionEnabled(true);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertTrue("Factory flag true must propagate to job", job.isSensitiveFileProtectionEnabled());
    }

    /** Default factory does NOT propagate false to the job. */
    @Test(timeout = 30000)
    public void factoryFlagDefaultDoesNotPropagateFalse() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertTrue("Default factory flag must propagate as TRUE", job.isSensitiveFileProtectionEnabled());
    }

    // ── Bypass signature propagation ──────────────────────────────

    /** Bypass signature set on the factory propagates to the job created by nextJob(). */
    @Test(timeout = 30000)
    public void factoryBypassSignaturePropagatesToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        String sig = SensitiveFileBypassTrailer.sign("shared-secret", factory.getTaskId());
        factory.setSensitiveFileBypassSignature(sig);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals(sig, job.getSensitiveFileBypassSignature());
    }

    /** {@link CodingAgentJobFactory#setSensitiveFileBypassSignature} with null clears the value. */
    @Test(timeout = 30000)
    public void factoryBypassSignatureNullClearsValue() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        factory.setSensitiveFileBypassSignature("something");
        factory.setSensitiveFileBypassSignature(null);
        assertNull(factory.getSensitiveFileBypassSignature());
    }

    // ── Wire format ───────────────────────────────────────────────

    /**
     * The default (true) is not emitted in the wire format — the field
     * is only serialized when explicitly set to false. This keeps the
     * wire format compact and the diff small.
     */
    @Test(timeout = 30000)
    public void wireFormatOmitsFlagWhenDefault() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        String encoded = job.encode();
        assertFalse("Default (true) must not appear in wire format: " + encoded,
                encoded.contains("sensitiveFileProtectionEnabled"));
    }

    /** The flag is emitted in the wire format when explicitly set to false. */
    @Test(timeout = 30000)
    public void wireFormatIncludesFlagWhenFalse() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        job.setSensitiveFileProtectionEnabled(false);
        String encoded = job.encode();
        assertTrue("Expected sensitiveFileProtectionEnabled:=false in: " + encoded,
                encoded.contains("sensitiveFileProtectionEnabled:=false"));
    }

    /** The flag survives an encode/decode round-trip. */
    @Test(timeout = 30000)
    public void wireFormatRoundTrip() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        job.setSensitiveFileProtectionEnabled(false);

        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
        assertFalse("Flag false must survive round-trip", restored.isSensitiveFileProtectionEnabled());
    }

    /** Default-true flag survives a round-trip without false flips. */
    @Test(timeout = 30000)
    public void wireFormatRoundTripDefaultIsTrue() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
        assertTrue("Default flag must remain true after round-trip",
                restored.isSensitiveFileProtectionEnabled());
    }

    /** Bypass signature round-trips through encode/decode. */
    @Test(timeout = 30000)
    public void bypassSignatureRoundTrips() {
        CodingAgentJob job = new CodingAgentJob("t1", "hello");
        String sig = SensitiveFileBypassTrailer.sign("s3cret", "t1");
        job.setSensitiveFileBypassSignature(sig);

        CodingAgentJob restored = GitManagedJobSerializationTest.roundTrip(job);
        assertEquals(sig, restored.getSensitiveFileBypassSignature());
    }

    // ── Factory deserialisation ───────────────────────────────────

    /** Factory's {@code set("sensitiveFileProtectionEnabled", "false")} deserialises correctly. */
    @Test(timeout = 30000)
    public void factorySetRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("prompt");
        assertTrue(factory.isSensitiveFileProtectionEnabled());

        factory.set("sensitiveFileProtectionEnabled", "false");
        assertFalse(factory.isSensitiveFileProtectionEnabled());

        factory.set("sensitiveFileProtectionEnabled", "true");
        assertTrue(factory.isSensitiveFileProtectionEnabled());
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Counts non-overlapping occurrences of {@code needle} in
     * {@code haystack}. Used to assert that exactly one
     * {@code Sensitive-File-Bypass} trailer remains in a commit message
     * after the strip-then-append sequence.
     */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return count;
            count++;
            from = idx + needle.length();
        }
    }
}
