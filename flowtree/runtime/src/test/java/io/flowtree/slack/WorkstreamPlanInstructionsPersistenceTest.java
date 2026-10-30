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

import fi.iki.elonen.NanoHTTPD;
import io.flowtree.api.FlowTreeApiEndpoint;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;
import io.flowtree.workstream.WorkstreamMetadataStore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Round-trips {@code planInstructions} through the controller's HTTP API with
 * a YAML file and a {@link WorkstreamMetadataStore} wired as the controller
 * wires them: the value a registration or update supplies is stored in the
 * database rather than the YAML, the listing returns it in the same shape as
 * before, and a restart reads it back.
 */
public class WorkstreamPlanInstructionsPersistenceTest extends TestSuiteBase {

    /** Plan instructions supplied by the requests below. */
    private static final String PLAN = "Investigate and fix the \"flaky\" retry loop.";

    /** Scratch directory holding the YAML and the database. */
    private Path dir;
    /** The workstreams YAML the listener persists to. */
    private File yaml;
    /** The metadata store attached to the configuration. */
    private WorkstreamMetadataStore store;
    /** Notifier holding the registered workstreams. */
    private SlackNotifier notifier;
    /** Listener that persists registrations through the configuration. */
    private SlackListener listener;
    /** The HTTP endpoint under test. */
    private FlowTreeApiEndpoint endpoint;

    /** Loads a one-workstream YAML, attaches a store, and starts the endpoint. */
    @Before
    public void startController() throws IOException {
        dir = Files.createTempDirectory("plan-instructions-persistence");
        yaml = dir.resolve("workstreams.yaml").toFile();
        Files.writeString(yaml.toPath(), "workstreams:\n"
                + "  - workstreamId: \"ws-existing\"\n"
                + "    channelId: \"C_EXISTING\"\n"
                + "    channelName: \"#existing\"\n"
                + "    defaultBranch: \"feature/existing\"\n", StandardCharsets.UTF_8);
        store = openStore();

        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(yaml);
        config.attachMetadataStore(store, yaml);
        notifier = new SlackNotifier(null);
        listener = new SlackListener(notifier);
        listener.setWorkstreamConfig(config, yaml);
        for (Workstream ws : config.toWorkstreams()) {
            listener.registerWorkstream(ws);
        }

        endpoint = new FlowTreeApiEndpoint(0, notifier);
        endpoint.setListener(listener);
        endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    /** Stops the endpoint, closes the store and removes the scratch directory. */
    @After
    public void stopController() throws IOException {
        if (endpoint != null) endpoint.stop();
        if (store != null) store.close();
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    /** Opens the store on the scratch directory's database. */
    private WorkstreamMetadataStore openStore() {
        WorkstreamMetadataStore s = new WorkstreamMetadataStore(
                dir.resolve(WorkstreamMetadataStore.DATABASE_NAME).toString());
        assertTrue(s.initialize());
        return s;
    }

    /**
     * Sends a request to the endpoint and returns the response body.
     *
     * @param method the HTTP method
     * @param path   the request path
     * @param body   the JSON body, or {@code null} for none
     * @return the response body
     * @throws IOException when the request fails or does not return 200
     */
    private String request(String method, String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(
                "http://localhost:" + endpoint.getListeningPort() + path).toURL().openConnection();
        conn.setRequestMethod(method);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        try (InputStream in = status == 200 ? conn.getInputStream() : conn.getErrorStream()) {
            String response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("unexpected status; body: " + response, 200, status);
            return response;
        }
    }

    /** Returns the YAML file's current text. */
    private String yamlText() throws IOException {
        return Files.readString(yaml.toPath(), StandardCharsets.UTF_8);
    }

    /**
     * Simulates a controller restart: reopens the store, reloads the YAML,
     * and returns the named workstream as the controller would register it.
     */
    private Workstream reloadAfterRestart(String workstreamId) throws IOException {
        store.close();
        store = openStore();
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(yaml);
        assertEquals("nothing is left in the YAML to migrate", 0,
                reloaded.attachMetadataStore(store, yaml));
        for (Workstream ws : reloaded.toWorkstreams()) {
            if (workstreamId.equals(ws.getWorkstreamId())) return ws;
        }
        fail("workstream " + workstreamId + " missing after restart");
        return null;
    }

    /**
     * A registration's plan instructions land in the store, not the YAML; the
     * listing returns them; and they are read back after a restart.
     */
    @Test(timeout = 30000)
    public void registrationStoresPlanInstructionsOutsideYaml() throws IOException {
        request("POST", "/api/workstreams", "{\"defaultBranch\":\"project/plan-persist\","
                + "\"baseBranch\":\"master\","
                + "\"planInstructions\":\"Investigate and fix the \\\"flaky\\\" retry loop.\","
                + "\"channelName\":\"w-plan-persist\"}");

        Workstream registered = notifier.findWorkstreamByBranch("project/plan-persist");
        assertNotNull(registered);
        String id = registered.getWorkstreamId();
        assertEquals(PLAN, store.get(id, WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
        String text = yamlText();
        assertTrue("the registration itself is persisted to the YAML", text.contains("project/plan-persist"));
        assertFalse("plan instructions must not be written to the YAML:\n" + text,
                text.contains("planInstructions"));

        String listing = request("GET", "/api/workstreams", null);
        assertTrue("listing keeps the planInstructions field: " + listing,
                listing.contains("\"planInstructions\":\"Investigate and fix the \\\"flaky\\\" retry loop.\""));

        assertEquals(PLAN, reloadAfterRestart(id).getPlanInstructions());
    }

    /** An update's plan instructions land in the store, not the YAML, and survive a restart. */
    @Test(timeout = 30000)
    public void updateStoresPlanInstructionsOutsideYaml() throws IOException {
        request("POST", "/api/workstreams/ws-existing/update",
                "{\"planInstructions\":\"Investigate and fix the \\\"flaky\\\" retry loop.\"}");

        assertEquals(PLAN, store.get("ws-existing", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
        assertFalse(yamlText().contains("planInstructions"));
        assertEquals(PLAN, reloadAfterRestart("ws-existing").getPlanInstructions());
    }

    /** Deleting a workstream removes its metadata once the YAML no longer names it. */
    @Test(timeout = 30000)
    public void deletingAWorkstreamRemovesItsMetadata() throws IOException {
        request("POST", "/api/workstreams/ws-existing/update",
                "{\"planInstructions\":\"Investigate and fix the \\\"flaky\\\" retry loop.\"}");
        Workstream existing = null;
        for (Workstream ws : notifier.getWorkstreams().values()) {
            if ("ws-existing".equals(ws.getWorkstreamId())) existing = ws;
        }
        assertNotNull(existing);

        assertTrue(listener.unregisterAndPersistWorkstream(existing));
        assertFalse(yamlText().contains("ws-existing"));
        assertNull(store.get("ws-existing", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
    }
}
