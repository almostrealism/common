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

import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Builds and verifies the {@code Sensitive-File-Bypass} commit-message trailer
 * that authorises a specific job to modify files normally protected by the
 * repo-level rules in
 * {@code tools/ci/agent-protection/validate-agent-commit.sh}
 * (RULE 1 — test files, RULE 3 — CI/workflow files).
 *
 * <h2>Threat model</h2>
 * <p>The agent controls {@code commit.txt} and every file in its working tree.
 * It must therefore not be able to authorise a bypass itself. The design:</p>
 * <ol>
 *   <li>The controller signs a per-job HMAC-SHA256 token using a shared secret
 *       ({@code AR_AGENT_BYPASS_SECRET}) at submission time and stores the
 *       signature on the {@link CodingAgentJobFactory} (see
 *       {@link CodingAgentJobFactory#setSensitiveFileBypassSignature(String)}).
 *       The signing secret is not in the agent's environment, so the agent
 *       cannot produce a valid signature itself.</li>
 *   <li>The harness — running after the agent has exited — strips any
 *       agent-supplied instance of the {@code Sensitive-File-Bypass} trailer
 *       from the commit message and appends a single controller-signed
 *       trailer. The strip-then-replace step is what prevents the agent from
 *       copying a previous job's trailer: anything the agent writes is removed
 *       and only the controller's signature remains.</li>
 *   <li>CI verifies the trailer's HMAC signature using the same shared secret
 *       (see {@code tools/ci/agent-protection/verify-sensitive-bypass.sh}).
 *       A commit is allowed past the sensitive-file rules only when the
 *       signature is valid.</li>
 * </ol>
 *
 * <h2>Trailer format</h2>
 * <pre>{@code
 * Sensitive-File-Bypass: <job-id>=<base64(HMAC-SHA256(secret, "<job-id>"))>
 * }</pre>
 * <p>The payload signed is the job ID alone — sufficient to bind the trailer
 * to a specific job. The base64 encoding uses the URL-safe alphabet without
 * padding to match the encoding the shell-side helper expects.</p>
 *
 * @author Michael Murray
 */
public final class SensitiveFileBypassTrailer implements ConsoleFeatures {

    /** Trailer key as it appears in the commit message. */
    public static final String TRAILER_KEY = "Sensitive-File-Bypass";

    /**
     * Pattern matching a line that introduces the bypass trailer, allowing
     * optional leading whitespace and case-insensitive key matching. The
     * {@link #TRAILER_KEY} is fixed in practice but the matcher is liberal so
     * that the harness reliably strips any agent attempt to forge a similar
     * key (e.g. {@code "sensitive-file-bypass:"}, {@code "SENSITIVE-FILE-BYPASS:"}).
     */
    private static final Pattern TRAILER_LINE = Pattern.compile(
            "(?im)^[ \t]*Sensitive-File-Bypass[ \t]*:.*$");

    /** Length (in bytes) of the Base64-URL-safe HMAC-SHA256 signature. */
    public static final int SIGNATURE_LENGTH = 43;

    /**
     * Returns the commit message with any agent-supplied
     * {@code Sensitive-File-Bypass} trailer lines removed, plus the
     * controller-signed trailer appended on its own line if
     * {@code signature} is non-null. The original message is preserved
     * verbatim aside from those two edits.
     *
     * <p>When {@code signature} is {@code null} the trailer is NOT appended,
     * and the returned message is the agent message with its own trailer
     * (if any) stripped. This is the correct behaviour for jobs where the
     * protection is enabled: an attempt by the agent to include a trailer
     * is removed and nothing replaces it.</p>
     *
     * @param message   the commit message produced from {@code commit.txt} or
     *                  the harness fallback; may be null/empty
     * @param jobId     the job ID to embed in the trailer; required when
     *                  {@code signature} is non-null
     * @param signature base64 HMAC-SHA256 signature for {@code jobId}, or
     *                  {@code null} to skip the append
     * @return the cleaned commit message, never null
     */
    public static String applyToMessage(String message, String jobId, String signature) {
        String cleaned = stripTrailer(message == null ? "" : message);
        if (signature == null || signature.isEmpty() || jobId == null || jobId.isEmpty()) {
            return cleaned;
        }
        return cleaned + (cleaned.endsWith("\n") ? "" : "\n")
                + TRAILER_KEY + ": " + jobId + "=" + signature + "\n";
    }

    /**
     * Returns the input string with every line that introduces the
     * {@code Sensitive-File-Bypass} trailer removed. Whitespace-only
     * differences (case, leading whitespace, trailing whitespace) are
     * tolerated so the harness reliably strips any agent attempt to
     * disguise the trailer key.
     *
     * <p>Trailer lines are removed without disturbing the rest of the
     * message: each surviving line keeps its trailing newline and the
     * separator that preceded the next surviving line. This makes the
     * method robust against an arbitrary number of consecutive trailers
     * (the agent can write 0, 1, 2, 3+ back-to-back {@code
     * Sensitive-File-Bypass:} lines and surrounding non-trailer content
     * will still appear on its own line). The security property &mdash;
     * every agent-supplied instance of the trailer is removed &mdash; is
     * preserved unconditionally.</p>
     *
     * @param message the original commit message
     * @return the message with bypass trailer lines removed
     */
    public static String stripTrailer(String message) {
        if (message == null || message.isEmpty()) return "";
        StringBuilder out = new StringBuilder(message.length());
        for (String line : message.split("\n", -1)) {
            // -1 keeps trailing empty fields so we preserve the final newline
            // of the original message. The last element may be "" when the
            // input ends with a newline; we re-emit it so the message structure
            // is preserved. The trailer line itself is dropped entirely and
            // we do NOT mutate the previous line's trailing newline. A prior
            // version of this method stripped the preceding newline so the
            // appended controller trailer would not introduce a blank gap,
            // but that approach was incorrect for an arbitrary number of
            // consecutive trailers: the second (and later) trailers would not
            // find a trailing newline to strip, so the next non-trailer line
            // ended up concatenated to the previous non-trailer line. Leaving
            // the previous newline in place and skipping the trailer line
            // entirely preserves the original message structure exactly,
            // regardless of how many trailers appear consecutively.
            if (TRAILER_LINE.matcher(line).matches()) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * Computes the base64 (URL-safe, no padding) HMAC-SHA256 of
     * {@code jobId} using {@code secret}. Used by the controller at job
     * submission time to produce the signature that the harness appends to
     * the commit message. The result is a stable 43-character string that
     * matches the format the shell-side verification helper
     * ({@code tools/ci/agent-protection/verify-sensitive-bypass.sh})
     * reconstructs.
     *
     * @param secret shared secret (e.g. value of {@code AR_AGENT_BYPASS_SECRET});
     *               treated as UTF-8 bytes
     * @param jobId  the job ID to sign
     * @return base64-URL-safe-no-padding HMAC-SHA256, or {@code null} when
     *         either argument is null/empty or the JVM crypto provider is
     *         unavailable (which should never happen on the supported JVMs)
     */
    public static String sign(String secret, String jobId) {
        if (secret == null || secret.isEmpty() || jobId == null || jobId.isEmpty()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(jobId.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            // Cannot happen on any supported JVM (HmacSHA256 is required),
            // but we log a warning and return null rather than crash the job
            // — without a signature the harness simply does not append a
            // trailer, and CI will block the commit. ConsoleFeatures.warn is
            // an instance method, so fall through to the static root() console.
            Console.root().warn(
                    "Could not compute HMAC for sensitive-file bypass: " + e.getMessage());
            return null;
        }
    }
}
