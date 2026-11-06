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

import io.flowtree.git.GitOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Maven installation a job's builds run with: whatever {@code mvn}
 * resolves to on this node's (augmented) {@code PATH}, identified by the
 * version it reports.
 *
 * <p>The installation is probed once per JVM by {@link #node()}; every job on
 * the node shares the result, since every job launches the same {@code mvn}.</p>
 *
 * @author Michael Murray
 * @see MavenInstallSpace
 */
public class MavenInstallation {

    /** How long {@link #node()} waits for {@code mvn --version} before giving up. */
    public static final long PROBE_TIMEOUT_SECONDS = 60;

    /** Matches the version line of {@code mvn --version}. */
    private static final Pattern VERSION = Pattern.compile("Apache Maven (\\d+)\\.(\\d+)");

    /** Guards the one-time probe so concurrent jobs share a single {@code mvn --version} run. */
    private static final Object PROBE_LOCK = new Object();

    /** The node's installation, probed on first use and cached for the life of the JVM. */
    private static volatile MavenInstallation node;

    /** Major version, or {@code -1} when the version could not be determined. */
    private final int major;

    /** Minor version, or {@code -1} when the version could not be determined. */
    private final int minor;

    /**
     * Creates the installation that printed {@code versionOutput}.
     *
     * @param versionOutput the output of {@code mvn --version}; {@code null}
     *                      or unrecognised output means no usable Maven
     */
    MavenInstallation(String versionOutput) {
        Matcher m = VERSION.matcher(versionOutput == null ? "" : versionOutput);
        boolean found = m.find();
        this.major = found ? Integer.parseInt(m.group(1)) : -1;
        this.minor = found ? Integer.parseInt(m.group(2)) : -1;
    }

    /**
     * Returns the installation {@code mvn} resolves to on this node, running
     * {@code mvn --version} the first time it is asked for. Jobs preparing
     * concurrently on one node serialise on {@link #PROBE_LOCK}, so the probe
     * runs once and every later caller returns the cached result rather than
     * launching its own {@value #PROBE_TIMEOUT_SECONDS}-second {@code mvn}. A
     * probe that is interrupted is not cached, so the next caller probes again.
     *
     * @return the node's Maven installation
     */
    public static MavenInstallation node() {
        MavenInstallation cached = node;
        if (cached != null) return cached;
        synchronized (PROBE_LOCK) {
            if (node != null) return node;
            try {
                node = probe();
                return node;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new MavenInstallation(null);
            }
        }
    }

    /**
     * Returns whether this installation honours the {@code MAVEN_ARGS}
     * environment variable and the split local repository, both of which a
     * {@link MavenInstallSpace} relies on (Maven 3.9 or later).
     *
     * @return {@code true} for Maven 3.9 or later
     */
    public boolean supportsSplitRepository() {
        return major > 3 || (major == 3 && minor >= 9);
    }

    /**
     * Runs {@code mvn --version}, bounded by {@link #PROBE_TIMEOUT_SECONDS}.
     *
     * <p>The output goes to a temporary file rather than a pipe, so a hung
     * {@code mvn} cannot block a read past the timeout; on timeout the process
     * is destroyed and no output is reported.</p>
     *
     * @return the installation the output identifies; one that supports
     *         nothing when {@code mvn} could not be run or did not finish in time
     * @throws InterruptedException if interrupted while waiting for the probe
     */
    private static MavenInstallation probe() throws InterruptedException {
        Path output = null;
        try {
            output = Files.createTempFile("mvn-version-", ".txt");
            ProcessBuilder pb = new ProcessBuilder("mvn", "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            GitOperations.augmentPath(pb);
            Process p = pb.start();
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new MavenInstallation(null);
            }
            return new MavenInstallation(Files.readString(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new MavenInstallation(null);
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // a leftover temp file is harmless
                }
            }
        }
    }
}
