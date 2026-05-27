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

package io.flowtree.slack;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Verifies per-submission {@code agentEnv} merging: a submission may inject dynamic, per-job
 * environment (e.g. a short-lived bearer token) on top of the workstream's static {@code agentEnv},
 * which is what lets an external caller pass a per-turn token without baking it into workstream
 * config.
 */
public class FlowTreeApiEndpointAgentEnvTest extends TestSuiteBase {

    @Test(timeout = 5000)
    public void workstreamEnvUsedWhenSubmissionHasNoAgentEnv() {
        Map<String, String> ws = new HashMap<>();
        ws.put("MOSAIC_AGENT_MODE", "true");

        Map<String, String> merged = FlowTreeApiEndpoint.mergeAgentEnv(ws, "{\"prompt\":\"hi\"}");

        Assert.assertEquals("true", merged.get("MOSAIC_AGENT_MODE"));
        Assert.assertEquals(1, merged.size());
    }

    @Test(timeout = 5000)
    public void submissionAgentEnvInjectedWhenWorkstreamHasNone() {
        String body = "{\"prompt\":\"hi\",\"agentEnv\":{\"MOSAIC_AGENT_TOKEN\":\"tok-123\"}}";

        Map<String, String> merged = FlowTreeApiEndpoint.mergeAgentEnv(null, body);

        Assert.assertEquals("tok-123", merged.get("MOSAIC_AGENT_TOKEN"));
    }

    @Test(timeout = 5000)
    public void submissionWinsOnKeyCollision() {
        Map<String, String> ws = new HashMap<>();
        ws.put("MOSAIC_AGENT_MODE", "true");
        ws.put("SHARED", "workstream");
        String body = "{\"agentEnv\":{\"SHARED\":\"submission\",\"MOSAIC_AGENT_TOKEN\":\"tok\"}}";

        Map<String, String> merged = FlowTreeApiEndpoint.mergeAgentEnv(ws, body);

        Assert.assertEquals("true", merged.get("MOSAIC_AGENT_MODE"));   // workstream-only key kept
        Assert.assertEquals("submission", merged.get("SHARED"));        // submission overrides
        Assert.assertEquals("tok", merged.get("MOSAIC_AGENT_TOKEN"));   // submission-only key added
    }

    @Test(timeout = 5000)
    public void emptyWhenNeitherProvided() {
        Map<String, String> merged = FlowTreeApiEndpoint.mergeAgentEnv(null, "{\"prompt\":\"hi\"}");

        Assert.assertTrue(merged.isEmpty());
    }
}
