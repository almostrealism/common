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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * HTTP-level tests for the {@code selfNotify} validation in
 * {@link FlowTreeApiEndpoint#handleSubmit}: a coding-agent job (no
 * {@code command}) must be rejected with {@code selfNotify=true}, since it
 * has no use for it (a coding agent can already submit its own follow-up as
 * its last action). The check runs before workstream resolution or peer
 * connectivity, so no workstream needs to be registered and no node needs to
 * be connected for these tests.
 */
public class SelfNotifySubmitValidationTest extends FlowTreeApiSubmitTestBase {

    /** JSON parser for response bodies. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** selfNotify=true on a coding-agent submission (prompt, no command) is a 400. */
    @Test(timeout = 10000)
    public void selfNotifyRejectedForCodingAgentJob() throws IOException {
        String body = "{\"prompt\":\"Fix the bug.\",\"selfNotify\":true}";
        HttpURLConnection conn = openPost("/api/submit", body);
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("ok").asBoolean());
        assertTrue("error must explain selfNotify is shell-only: " + response.get("error"),
                response.get("error").asText().contains("selfNotify")
                        && response.get("error").asText().contains("shell"));
    }

    /** selfNotify absent (the default) never trips the shell-only rejection. */
    @Test(timeout = 10000)
    public void missingSelfNotifyIsNotRejected() throws IOException {
        String body = "{\"prompt\":\"Fix the bug.\"}";
        HttpURLConnection conn = openPost("/api/submit", body);
        // No workstream is registered, so this still fails -- but with a
        // DIFFERENT error than the selfNotify rejection, proving the
        // selfNotify check itself did not fire.
        assertEquals(400, conn.getResponseCode());
        JsonNode response = MAPPER.readTree(readErrorBody(conn));
        assertFalse(response.get("error").asText().contains("selfNotify"));
    }
}
