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

package io.flowtree.workstream;

import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Tests for {@link WorkstreamMetadataStore} and for the way
 * {@link WorkstreamConfig} moves workstream metadata out of the YAML into it:
 * the one-time migration on startup, its idempotence, the fallback to the YAML
 * when no store is available, and the store-before-YAML write order.
 */
public class WorkstreamMetadataStoreTest extends TestSuiteBase {

    /** A YAML written before the metadata store existed, carrying plan instructions inline. */
    private static final String LEGACY_YAML = "workstreams:\n"
            + "  - workstreamId: \"ws-a\"\n"
            + "    channelId: \"C_A\"\n"
            + "    channelName: \"#a\"\n"
            + "    defaultBranch: \"feature/a\"\n"
            + "    planInstructions: \"Investigate the flaky retry loop.\"\n"
            + "  - workstreamId: \"ws-b\"\n"
            + "    channelId: \"C_B\"\n"
            + "    channelName: \"#b\"\n"
            + "    defaultBranch: \"feature/b\"\n";

    /** Scratch directory holding the database and YAML for one test. */
    private Path dir;

    /** The store under test, reopened by tests that simulate a restart. */
    private WorkstreamMetadataStore store;

    /** Creates a scratch directory and opens a fresh store in it. */
    @Before
    public void openStore() throws IOException {
        dir = Files.createTempDirectory("workstream-metadata-test");
        store = newStore();
    }

    /** Closes the store and removes the scratch directory. */
    @After
    public void closeStore() throws IOException {
        if (store != null) store.close();
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    /** Opens a store on the scratch directory's database, asserting that it opened. */
    private WorkstreamMetadataStore newStore() {
        WorkstreamMetadataStore s = new WorkstreamMetadataStore(
                dir.resolve(WorkstreamMetadataStore.DATABASE_NAME).toString());
        assertTrue("store should open", s.initialize());
        return s;
    }

    /** Closes the store and opens it again, as a controller restart does. */
    private void restartStore() {
        store.close();
        store = newStore();
    }

    /** Writes {@code yaml} to a file in the scratch directory and returns it. */
    private File writeYaml(String yaml) throws IOException {
        File file = dir.resolve("workstreams.yaml").toFile();
        Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);
        return file;
    }

    /** Returns the named workstream from a list, failing when it is absent. */
    private static Workstream byId(List<Workstream> workstreams, String id) {
        for (Workstream ws : workstreams) {
            if (id.equals(ws.getWorkstreamId())) return ws;
        }
        fail("No workstream " + id);
        return null;
    }

    /** Values written by {@code putAll} read back individually and in one batch, and survive a restart. */
    @Test(timeout = 30000)
    public void putAllRoundTripsAndSurvivesRestart() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ws-1", "first");
        values.put("ws-2", "second");
        assertEquals(2, store.putAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS, values));

        assertEquals("first", store.get("ws-1", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
        assertNull(store.get("ws-3", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
        assertNull("values are keyed by name as well as workstream", store.get("ws-1", "other"));

        restartStore();
        Map<String, String> all = store.getAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS);
        assertEquals(values, all);
    }

    /** An unchanged value is not rewritten, a changed one is, and null or empty never deletes. */
    @Test(timeout = 30000)
    public void putAllWritesOnlyChangesAndNeverDeletes() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ws-1", "first");
        values.put("ws-2", "second");
        store.putAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS, values);

        Map<String, String> next = new LinkedHashMap<>();
        next.put("ws-1", "first");
        next.put("ws-2", "revised");
        next.put("ws-3", null);
        next.put("ws-4", "");
        assertEquals("only ws-2 changed", 1,
                store.putAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS, next));

        Map<String, String> cleared = new LinkedHashMap<>();
        cleared.put("ws-1", null);
        cleared.put("ws-2", "");
        assertEquals(0, store.putAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS, cleared));

        Map<String, String> all = store.getAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS);
        assertEquals(2, all.size());
        assertEquals("first", all.get("ws-1"));
        assertEquals("revised", all.get("ws-2"));
    }

    /** A value far larger than a YAML scalar should carry is stored and read back intact. */
    @Test(timeout = 30000)
    public void storesLargeValues() throws IOException {
        StringBuilder large = new StringBuilder();
        while (large.length() < 200_000) {
            large.append("Plan step ").append(large.length()).append(": é\"quoted\"\n");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ws-big", large.toString());
        store.putAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS, values);

        restartStore();
        assertEquals(large.toString(),
                store.get("ws-big", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
    }

    /** {@code removeWorkstream} deletes only that workstream's values. */
    @Test(timeout = 30000)
    public void removeWorkstreamDeletesOnlyItsValues() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ws-1", "first");
        values.put("ws-2", "second");
        store.putAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS, values);

        assertEquals(1, store.removeWorkstream("ws-1"));
        assertNull(store.get("ws-1", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
        assertEquals("second", store.get("ws-2", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
    }

    /** A closed store refuses reads and writes instead of answering as if it were empty. */
    @Test(timeout = 30000)
    public void closedStoreThrows() {
        store.close();
        assertFalse(store.isOpen());
        try {
            store.getAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS);
            fail("a closed store must not read as empty");
        } catch (IOException expected) {
            // expected
        }
        try {
            store.putAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS, Map.of("ws-1", "x"));
            fail("a closed store must not accept writes");
        } catch (IOException expected) {
            // expected
        }
    }

    /**
     * Attaching the store to a configuration loaded from an old YAML moves the
     * plan instructions into the store and rewrites the YAML without them,
     * keeping every configuration field.
     */
    @Test(timeout = 30000)
    public void migrationMovesPlanInstructionsOutOfYaml() throws IOException {
        File yaml = writeYaml(LEGACY_YAML);
        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(yaml);

        assertEquals(1, config.attachMetadataStore(store, yaml));

        assertEquals("Investigate the flaky retry loop.",
                store.get("ws-a", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
        String rewritten = Files.readString(yaml.toPath(), StandardCharsets.UTF_8);
        assertFalse("YAML must no longer carry plan instructions:\n" + rewritten,
                rewritten.contains("planInstructions"));
        assertTrue(rewritten.contains("feature/a"));
        assertTrue(rewritten.contains("feature/b"));

        List<Workstream> workstreams = config.toWorkstreams();
        assertEquals("Investigate the flaky retry loop.",
                byId(workstreams, "ws-a").getPlanInstructions());
        assertNull(byId(workstreams, "ws-b").getPlanInstructions());
    }

    /**
     * A second start — a fresh configuration loaded from the rewritten YAML and
     * a reopened store — migrates nothing, leaves the YAML file untouched, and
     * still serves the plan instructions from the store.
     */
    @Test(timeout = 30000)
    public void migrationRunTwiceIsANoOp() throws IOException {
        File yaml = writeYaml(LEGACY_YAML);
        WorkstreamConfig.loadFromYaml(yaml).attachMetadataStore(store, yaml);
        byte[] afterFirst = Files.readAllBytes(yaml.toPath());
        long modifiedAfterFirst = yaml.lastModified();

        restartStore();
        WorkstreamConfig second = WorkstreamConfig.loadFromYaml(yaml);
        assertEquals(0, second.attachMetadataStore(store, yaml));

        assertArrayEquals("a no-op start must not rewrite the YAML",
                afterFirst, Files.readAllBytes(yaml.toPath()));
        assertEquals(modifiedAfterFirst, yaml.lastModified());
        assertEquals("Investigate the flaky retry loop.",
                byId(second.toWorkstreams(), "ws-a").getPlanInstructions());
        assertEquals(1, store.getAll(WorkstreamMetadataStore.PLAN_INSTRUCTIONS).size());
    }

    /**
     * A controller with no store (its database could not be opened) still runs
     * on an old YAML: the plan instructions are served from it and kept in it
     * across a save, so nothing is lost before a later start migrates them.
     */
    @Test(timeout = 30000)
    public void oldYamlStillWorksWithoutAStore() throws IOException {
        File yaml = writeYaml(LEGACY_YAML);
        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(yaml);

        List<Workstream> workstreams = config.toWorkstreams();
        assertEquals("Investigate the flaky retry loop.",
                byId(workstreams, "ws-a").getPlanInstructions());

        config.syncAndSave(workstreams, yaml);
        WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(yaml);
        assertEquals("Investigate the flaky retry loop.",
                byId(reloaded.toWorkstreams(), "ws-a").getPlanInstructions());
    }

    /**
     * When the store write fails partway through migration the YAML is left
     * exactly as it was, the store is detached, and the configuration keeps
     * serving and saving the plan instructions from the YAML.
     */
    @Test(timeout = 30000)
    public void failedMigrationLeavesYamlIntact() throws IOException {
        File yaml = writeYaml(LEGACY_YAML);
        byte[] original = Files.readAllBytes(yaml.toPath());
        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(yaml);
        store.close();

        try {
            config.attachMetadataStore(store, yaml);
            fail("migration into a closed store must fail");
        } catch (IOException expected) {
            // expected
        }

        assertArrayEquals(original, Files.readAllBytes(yaml.toPath()));
        List<Workstream> workstreams = config.toWorkstreams();
        assertEquals("Investigate the flaky retry loop.",
                byId(workstreams, "ws-a").getPlanInstructions());
        config.syncAndSave(workstreams, yaml);
        assertTrue("with the store detached the YAML keeps the value",
                Files.readString(yaml.toPath(), StandardCharsets.UTF_8)
                        .contains("Investigate the flaky retry loop."));
    }

    /**
     * With a store attached, saving writes plan instructions to the store and
     * not to the YAML; when the store write fails, the YAML is not rewritten.
     */
    @Test(timeout = 30000)
    public void syncAndSaveWritesStoreBeforeYaml() throws IOException {
        File yaml = writeYaml(LEGACY_YAML);
        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(yaml);
        config.attachMetadataStore(store, yaml);

        List<Workstream> workstreams = config.toWorkstreams();
        byId(workstreams, "ws-b").setPlanInstructions("Draft the ingestion plan.");
        config.syncAndSave(workstreams, yaml);

        assertEquals("Draft the ingestion plan.",
                store.get("ws-b", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
        assertFalse(Files.readString(yaml.toPath(), StandardCharsets.UTF_8)
                .contains("planInstructions"));

        byte[] beforeFailure = Files.readAllBytes(yaml.toPath());
        store.close();
        byId(workstreams, "ws-b").setDefaultBranch("feature/b-renamed");
        byId(workstreams, "ws-b").setPlanInstructions("Revised plan.");
        try {
            config.syncAndSave(workstreams, yaml);
            fail("a failed store write must fail the save");
        } catch (IOException expected) {
            // expected
        }
        assertArrayEquals("the YAML must not be written after a failed store write",
                beforeFailure, Files.readAllBytes(yaml.toPath()));
    }

    /** Removing a deleted workstream's metadata clears its row and leaves the rest. */
    @Test(timeout = 30000)
    public void removeWorkstreamMetadataClearsDeletedWorkstream() throws IOException {
        File yaml = writeYaml(LEGACY_YAML);
        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(yaml);
        config.attachMetadataStore(store, yaml);

        config.removeWorkstreamMetadata("ws-a");
        assertNull(store.get("ws-a", WorkstreamMetadataStore.PLAN_INSTRUCTIONS));
    }
}
