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

package io.flowtree.jobs.agent;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies the binary discovery order used by {@link OpencodeBinaryLocator}.
 */
public class OpencodeBinaryLocatorTest extends TestSuiteBase {

    /** A {@code user.home} value used to derive the operator-managed install path. */
    private static final String HOME = "/home/agent";

    /** The operator-managed install path the locator should probe second. */
    private static final Path MANAGED_PATH = Paths.get(HOME, ".flowtree", "bin", "opencode");

    /** {@code OPENCODE_BIN} env value used in tests that exercise the env-var branch. */
    private static final Path ENV_PATH = Paths.get("/usr/local/custom/opencode");

    /** A second {@code PATH} entry that the locator should walk. */
    private static final Path PATH_HIT = Paths.get("/opt/tools/opencode");

    /**
     * Builds an environment lookup function from {@code map} that returns
     * {@code null} for any key not present.
     */
    private static Function<String, String> envOf(Map<String, String> map) {
        return map::get;
    }

    /** When {@code OPENCODE_BIN} resolves to a runnable binary, the locator returns it. */
    @Test(timeout = 5000)
    public void resolvesFromEnvVariableWhenPresent() {
        Map<String, String> env = new HashMap<>();
        env.put(OpencodeBinaryLocator.ENV_OPENCODE_BIN, ENV_PATH.toString());
        Set<Path> executable = Set.of(ENV_PATH);
        OpencodeBinaryLocator locator = new OpencodeBinaryLocator(
                envOf(env), name -> HOME, executable::contains);

        assertEquals(ENV_PATH, locator.locate());
    }

    /** A non-executable {@code OPENCODE_BIN} value is rejected and discovery falls through. */
    @Test(timeout = 5000)
    public void envVariableMustResolveToExecutable() {
        Map<String, String> env = new HashMap<>();
        env.put(OpencodeBinaryLocator.ENV_OPENCODE_BIN, "/does/not/exist");
        Set<Path> executable = Set.of(MANAGED_PATH);
        OpencodeBinaryLocator locator = new OpencodeBinaryLocator(
                envOf(env), name -> HOME, executable::contains);

        assertEquals(MANAGED_PATH, locator.locate());
    }

    /** Without env var, the locator falls back to {@code ~/.flowtree/bin/opencode}. */
    @Test(timeout = 5000)
    public void resolvesFromManagedInstallPath() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");
        Set<Path> executable = Set.of(MANAGED_PATH);
        OpencodeBinaryLocator locator = new OpencodeBinaryLocator(
                envOf(env), name -> HOME, executable::contains);

        assertEquals(MANAGED_PATH, locator.locate());
    }

    /** When neither env nor managed path matches, the locator walks {@code PATH}. */
    @Test(timeout = 5000)
    public void walksPathEntriesInOrder() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin" + File.pathSeparator + PATH_HIT.getParent().toString());
        Set<Path> executable = Set.of(PATH_HIT);
        OpencodeBinaryLocator locator = new OpencodeBinaryLocator(
                envOf(env), name -> HOME, executable::contains);

        assertEquals(PATH_HIT, locator.locate());
    }

    /** When nothing resolves, {@link AgentRunnerNotAvailableException} is thrown. */
    @Test(timeout = 5000)
    public void throwsWhenNothingFound() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/usr/bin");
        Set<Path> executable = new HashSet<>();
        OpencodeBinaryLocator locator = new OpencodeBinaryLocator(
                envOf(env), name -> HOME, executable::contains);

        try {
            locator.locate();
            fail("expected AgentRunnerNotAvailableException");
        } catch (AgentRunnerNotAvailableException expected) {
            String msg = expected.getMessage();
            assertNotNull(msg);
            assertTrue("message should enumerate locations: " + msg,
                    msg.contains("opencode binary not found"));
            assertTrue("message should mention env var: " + msg,
                    msg.contains(OpencodeBinaryLocator.ENV_OPENCODE_BIN));
            assertTrue("message should mention PATH: " + msg, msg.contains("PATH"));
        }
    }

    /** The env-var branch takes precedence over the managed install. */
    @Test(timeout = 5000)
    public void envVariableBeatsManagedPath() {
        Map<String, String> env = new HashMap<>();
        env.put(OpencodeBinaryLocator.ENV_OPENCODE_BIN, ENV_PATH.toString());
        Set<Path> executable = Set.of(ENV_PATH, MANAGED_PATH);
        OpencodeBinaryLocator locator = new OpencodeBinaryLocator(
                envOf(env), name -> HOME, executable::contains);

        assertEquals(ENV_PATH, locator.locate());
    }
}
