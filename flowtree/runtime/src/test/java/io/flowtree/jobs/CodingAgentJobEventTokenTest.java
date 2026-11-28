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

import static org.junit.Assert.assertEquals;

/**
 * Tests that the token counts added to {@link CodingAgentJobEvent} survive the
 * status-post JSON wire (plan Stage 0): a worker builds the event with
 * {@link CodingAgentJobEvent#withTokenInfo}, serialises it, and the controller
 * reconstructs it with {@link JobCompletionEvent#fromStatusBody}.
 */
public class CodingAgentJobEventTokenTest extends TestSuiteBase {

    /** The four token counts round-trip through {@code toJson} / {@code fromStatusBody}. */
    @Test(timeout = 30000)
    public void tokensSurviveStatusBodyRoundTrip() {
        CodingAgentJobEvent event = CodingAgentJobEvent.success("job-tok", "a job")
                .withClaudeCodeInfo("prompt text", "sess-1", 0)
                .withTokenInfo(1234, 567, 89, 42);

        String body = event.toJson();

        JobCompletionEvent restored = JobCompletionEvent.fromStatusBody(
                "job-tok", JobCompletionEvent.Status.SUCCESS, "a job", body);

        assertEquals(1234L, restored.getInputTokens());
        assertEquals(567L, restored.getOutputTokens());
        assertEquals(89L, restored.getCacheReadTokens());
        assertEquals(42L, restored.getCacheWriteTokens());
    }

    /** A base event with no token capture reports zero for every token measure. */
    @Test(timeout = 30000)
    public void baseEventReportsZeroTokens() {
        JobCompletionEvent event = JobCompletionEvent.success("job-none", "no tokens");
        assertEquals(0L, event.getInputTokens());
        assertEquals(0L, event.getOutputTokens());
        assertEquals(0L, event.getCacheReadTokens());
        assertEquals(0L, event.getCacheWriteTokens());
    }
}
