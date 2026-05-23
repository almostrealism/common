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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves the path to the {@code opencode} CLI binary on the executor host.
 *
 * <p>Discovery order:</p>
 * <ol>
 *   <li>{@code OPENCODE_BIN} environment variable (absolute path).</li>
 *   <li>{@code ~/.flowtree/bin/opencode} (operator-managed install).</li>
 *   <li>{@code PATH} lookup of {@code opencode}.</li>
 * </ol>
 *
 * <p>The locator intentionally has no per-workstream override hook. Per-host
 * environment variables and the operator-managed default path are sufficient
 * for first-class support; per-workstream binary overrides can be added later
 * if a real need arises.</p>
 */
final class OpencodeBinaryLocator {

    /** Name of the opencode executable looked up on {@code PATH}. */
    static final String BINARY_NAME = "opencode";

    /** Environment variable that, when set, takes absolute precedence. */
    static final String ENV_OPENCODE_BIN = "OPENCODE_BIN";

    /** Source of environment variables; overrideable for tests. */
    private final Function<String, String> envLookup;

    /** Override for {@code System.getProperty("user.home")}; overrideable for tests. */
    private final Function<String, String> propertyLookup;

    /** Override for {@code PATH} probing; overrideable for tests. */
    private final Function<Path, Boolean> isExecutableFile;

    /** Constructs a locator that consults the live process environment and filesystem. */
    OpencodeBinaryLocator() {
        this(System::getenv, System::getProperty, OpencodeBinaryLocator::defaultIsExecutableFile);
    }

    /**
     * Constructs a locator with overridable environment/property/filesystem
     * accessors for unit testing.
     *
     * @param envLookup        returns the value of an environment variable
     * @param propertyLookup   returns the value of a system property
     * @param isExecutableFile returns whether a path is a regular executable file
     */
    OpencodeBinaryLocator(Function<String, String> envLookup,
                          Function<String, String> propertyLookup,
                          Function<Path, Boolean> isExecutableFile) {
        this.envLookup = envLookup;
        this.propertyLookup = propertyLookup;
        this.isExecutableFile = isExecutableFile;
    }

    /**
     * Locates the opencode binary using the documented discovery order.
     *
     * @return the absolute path to a runnable opencode binary
     * @throws AgentRunnerNotAvailableException when no candidate resolves
     *         successfully; the message enumerates every location checked
     */
    Path locate() {
        List<String> checked = new ArrayList<>();

        String envValue = envLookup.apply(ENV_OPENCODE_BIN);
        if (envValue != null && !envValue.isEmpty()) {
            Path p = Paths.get(envValue);
            checked.add(ENV_OPENCODE_BIN + "=" + envValue);
            if (Boolean.TRUE.equals(isExecutableFile.apply(p))) {
                return p;
            }
        } else {
            checked.add(ENV_OPENCODE_BIN + " (unset)");
        }

        String home = propertyLookup.apply("user.home");
        if (home != null && !home.isEmpty()) {
            Path managed = Paths.get(home, ".flowtree", "bin", BINARY_NAME);
            checked.add(managed.toString());
            if (Boolean.TRUE.equals(isExecutableFile.apply(managed))) {
                return managed;
            }
        }

        Path onPath = lookupOnPath();
        checked.add("PATH (" + BINARY_NAME + ")");
        if (onPath != null) {
            return onPath;
        }

        throw new AgentRunnerNotAvailableException(
                "opencode binary not found. Checked: " + String.join(", ", checked)
                        + ". Set " + ENV_OPENCODE_BIN + " or install opencode under ~/.flowtree/bin/"
                        + " or place it on PATH.");
    }

    /**
     * Searches the {@code PATH} environment variable for an executable named
     * {@link #BINARY_NAME}.
     *
     * @return the first matching path, or {@code null} when none is found
     */
    private Path lookupOnPath() {
        String pathEnv = envLookup.apply("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        String separator = File.pathSeparator;
        for (String dir : pathEnv.split(Pattern.quote(separator))) {
            if (dir.isEmpty()) continue;
            Path candidate = Paths.get(dir, BINARY_NAME);
            if (Boolean.TRUE.equals(isExecutableFile.apply(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Default check that {@code path} points to a regular, executable file.
     *
     * @param path the candidate path
     * @return {@code true} when the file exists, is regular, and is executable
     */
    private static boolean defaultIsExecutableFile(Path path) {
        if (path == null) return false;
        File f = path.toFile();
        return f.isFile() && f.canExecute() && Files.isRegularFile(path);
    }
}
