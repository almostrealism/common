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

package io.flowtree.github;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The {@code X-Hub-Signature-256} value GitHub attaches to a webhook delivery:
 * {@code sha256=} followed by the lower-case hex HMAC-SHA256 of the raw request
 * body, keyed with the secret configured on the webhook.
 *
 * <p>The HMAC is computed over the body bytes exactly as they arrived, so a
 * receiver must verify the undecoded bytes rather than a string it has
 * re-encoded. The comparison is constant-time so the check does not leak how
 * many leading characters of a forged signature were right.</p>
 *
 * <p>This is a class rather than a record so the secret is never exposed by
 * an accessor or a generated {@code toString()}.</p>
 *
 * @author Michael Murray
 */
public final class GitHubWebhookSignature {

    /** The name of the header carrying the signature. */
    public static final String HEADER = "X-Hub-Signature-256";

    /** The algorithm prefix GitHub writes before the hex digest. */
    public static final String PREFIX = "sha256=";

    /** The webhook secret shared with GitHub. */
    private final String secret;

    /**
     * Creates a signature scheme keyed with {@code secret}.
     *
     * @param secret the webhook secret shared with GitHub
     */
    public GitHubWebhookSignature(String secret) {
        this.secret = secret;
    }

    /**
     * Computes the header value GitHub would send for {@code body}.
     *
     * @param body the raw request body
     * @return {@code sha256=} followed by the hex HMAC-SHA256 of {@code body}
     */
    public String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /**
     * Reports whether {@code header} is the correct signature of {@code body}.
     *
     * @param body   the raw request body
     * @param header the received {@code X-Hub-Signature-256} value, or
     *               {@code null} when the delivery carried none
     * @return {@code true} only when the header is present and matches; an
     *         absent, malformed or mismatched header, or a blank secret, is
     *         {@code false}
     */
    public boolean verifies(byte[] body, String header) {
        if (secret == null || secret.isBlank() || header == null || body == null) {
            return false;
        }
        byte[] expected = sign(body).getBytes(StandardCharsets.US_ASCII);
        byte[] received = header.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, received);
    }
}
