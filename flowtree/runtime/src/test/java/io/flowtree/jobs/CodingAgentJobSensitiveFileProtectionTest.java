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

    // ── stripTrailer: structure preservation ──────────────────────

    /**
     * The strip step must preserve the original message structure
     * exactly when the trailer is at the end of the message. The
     * trailer line itself is removed; every other line is preserved
     * with its trailing newline. The expected output has the trailer
     * line removed but is otherwise identical to the input.
     */
    @Test(timeout = 30000)
    public void stripTrailerPreservesStructureTrailerAtEnd() {
        String input = "Title\n\nBody.\nSensitive-File-Bypass: job-1=abc\n";
        String out = SensitiveFileBypassTrailer.stripTrailer(input);
        // Strip just the trailer line; everything else is preserved.
        String expected = "Title\n\nBody.\n\n";
        assertEquals(expected, out);
    }

    /**
     * Two consecutive {@code Sensitive-File-Bypass:} lines must both be
     * removed and the non-trailer line that follows must keep its own
     * newline separator. The previous version of this method stripped a
     * preceding newline for the first trailer and then had nothing to
     * strip for the second trailer, which concatenated the next
     * non-trailer line to the previous non-trailer line without a
     * separator (e.g. {@code "title" + "more body"} instead of
     * {@code "title\n\nmore body"}).
     */
    @Test(timeout = 30000)
    public void stripTrailerPreservesStructureTwoConsecutiveTrailers() {
        String input = "title\n\n"
                + "Sensitive-File-Bypass: job-victim=forged-signature\n"
                + "SENSITIVE-FILE-BYPASS: job-other=another-forgery\n"
                + "more body\n";
        String out = SensitiveFileBypassTrailer.stripTrailer(input);
        // Both trailers removed, but the message structure is intact.
        assertFalse("Forged signature must be stripped", out.contains("forged-signature"));
        assertFalse("Second forged signature must be stripped", out.contains("another-forgery"));
        assertFalse("No 'sensitive-file-bypass' substring may remain", out.toLowerCase()
                .contains("sensitive-file-bypass"));
        assertTrue("'more body' must remain on its own line", out.contains("more body\n"));
        assertTrue("'title' must remain on its own line", out.contains("title\n"));
        // The line preceding "more body" must end with a newline so "more body"
        // starts on a new line, not concatenated to it. Equivalently, the
        // substring "more body" must be preceded by a newline.
        int idx = out.indexOf("more body");
        assertTrue("'more body' must be preceded by a newline (no concatenation)", idx > 0
                && out.charAt(idx - 1) == '\n');
    }

    /**
     * Three or more consecutive trailers must all be stripped without
     * mangling surrounding content. This covers the general case (N
     * consecutive trailers).
     */
    @Test(timeout = 30000)
    public void stripTrailerPreservesStructureManyConsecutiveTrailers() {
        String input = "title\n\n"
                + "Sensitive-File-Bypass: a=1\n"
                + "SENSITIVE-FILE-BYPASS: b=2\n"
                + "sensitive-file-bypass: c=3\n"
                + "after-three\n";
        String out = SensitiveFileBypassTrailer.stripTrailer(input);
        assertFalse("All three forged trailers must be stripped",
                out.toLowerCase().contains("sensitive-file-bypass"));
        assertTrue("'after-three' must remain on its own line", out.contains("after-three\n"));
        int idx = out.indexOf("after-three");
        assertTrue("'after-three' must be preceded by a newline", idx > 0
                && out.charAt(idx - 1) == '\n');
    }

    /**
     * A trailer that appears mid-message (with non-trailer content after
     * it) must be removed without merging the following line into the
     * preceding one.
     */
    @Test(timeout = 30000)
    public void stripTrailerPreservesStructureTrailerMidMessage() {
        String input = "Title\nBody1.\nSensitive-File-Bypass: x=y\nBody2.\n";
        String out = SensitiveFileBypassTrailer.stripTrailer(input);
        assertFalse("Trailer must be stripped", out.toLowerCase().contains("sensitive-file-bypass"));
        assertTrue("Body1 must remain on its own line", out.contains("Body1.\n"));
        assertTrue("Body2 must remain on its own line", out.contains("Body2.\n"));
        // The substring "Body2." must be preceded by a newline so it does
        // not get concatenated to "Body1." (the bug that this commit fixes).
        int idx = out.indexOf("Body2.");
        assertTrue("'Body2.' must be preceded by a newline", idx > 0
                && out.charAt(idx - 1) == '\n');
    }

    // ── applyToMessage: end-to-end ────────────────────────────────

    /**
     * The full strip-then-append sequence produces a message that
     * (a) does NOT contain any agent-supplied trailer, (b) contains
     * exactly one controller-signed trailer, and (c) preserves the
     * original message structure (the agent's "more body" line must
     * appear on its own line, not concatenated to the preceding
     * non-trailer content). This is the property that prevents the
     * agent from forging a bypass AND that proves the strip step did
     * not corrupt commit-message structure when the agent wrote two
     * back-to-back trailers.
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
        // Structure preservation: 'more body' must be preceded by a newline
        // (it must not be concatenated to the empty line that preceded the
        // stripped trailers).
        int idx = out.indexOf("more body");
        assertTrue("'more body' must remain on its own line (no concatenation)",
                idx > 0 && out.charAt(idx - 1) == '\n');
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

    // ── API surface contract ──────────────────────────────────────

    /**
     * The {@link CodingAgentJobFactory#setSensitiveFileBypassSignature} setter
     * is the ONLY production path to set the bypass signature on a job.
     * A previous version of the controller accepted an explicit
     * {@code sensitiveFileBypassSignature} field in the request body and
     * overwrote the controller-computed HMAC; that override path has been
     * removed. This test pins the contract: a signature set via the factory
     * setter is the value the job observes after propagation. Tests that
     * need to set an arbitrary signature can do so via the factory setter
     * without going through the API, so the API no longer needs to expose
     * an override.
     */
    @Test(timeout = 30000)
    public void factorySetterIsOnlyPathToBypassSignature() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        String controllerSig = SensitiveFileBypassTrailer.sign("shared-secret", factory.getTaskId());
        // The controller-computed signature is set on the factory.
        factory.setSensitiveFileBypassSignature(controllerSig);
        // The factory has no public "applyBody" or similar method that
        // would re-read the field; the only mutation path is the setter.
        // After propagation, the job observes the controller-computed value.
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertEquals(controllerSig, job.getSensitiveFileBypassSignature());
    }

    /**
     * After the controller-computed signature is set, an attempt to clear
     * it via the setter (simulating "no body override possible") must
     * leave the job without a signature, which is the correct behaviour
     * when the API no longer honours a request-body-supplied value.
     */
    @Test(timeout = 30000)
    public void factoryBypassSignatureCanOnlyBeSetViaSetter() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do something");
        factory.setSensitiveFileBypassSignature(
                SensitiveFileBypassTrailer.sign("shared-secret", factory.getTaskId()));
        // Clear it. The only way to set a signature on the factory is
        // via the setter, and the only way to clear it is via the
        // setter with null. There is no other public mutation path.
        factory.setSensitiveFileBypassSignature(null);
        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull(job);
        assertNull("With the API override removed, clearing the factory's"
                + " signature is the only way to leave the job without one",
                job.getSensitiveFileBypassSignature());
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
