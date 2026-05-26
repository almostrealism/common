package io.flowtree.slack;

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
public class FlowTreeApiEndpointAgentEnvTest {

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
