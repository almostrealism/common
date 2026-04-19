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

package org.almostrealism.io;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Captures an identifying fingerprint of the JVM and host the current
 * process is executing on.
 *
 * <p>Useful whenever a process needs to emit a self-identifying record
 * so that logs, messages, or artifacts it produces can be traced back
 * to a specific container/machine/PID — especially in networked systems
 * where work can be routed to unexpected or transient peers.</p>
 */
public final class HostFingerprint {

    /**
     * Private constructor — this class holds only static helpers and must
     * not be instantiated.
     */
    private HostFingerprint() {
        // Static utility -- not instantiable.
    }

    /**
     * Returns a multi-line, human-readable fingerprint describing the
     * current JVM and its host environment. Safe to call at any time.
     * Never throws; fields that cannot be resolved render as
     * {@code unknown} / {@code <unset>}.
     *
     * @return the fingerprint text, ready for logging or message posting
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("==== HOST FINGERPRINT ====\n");

        sb.append("  hostname:            ").append(hostname()).append('\n');
        sb.append("  localAddress:        ").append(hostAddress()).append('\n');
        sb.append("  pid:                 ").append(ProcessHandle.current().pid()).append('\n');
        sb.append("  parentPid:           ").append(parentPid()).append('\n');
        sb.append("  user:                ").append(sysProp("user.name")).append('\n');
        sb.append("  cwd:                 ").append(sysProp("user.dir")).append('\n');
        sb.append("  javaVersion:         ").append(sysProp("java.version")).append('\n');
        sb.append("  javaVendor:          ").append(sysProp("java.vendor")).append('\n');
        sb.append("  osName:              ").append(sysProp("os.name")).append('\n');
        sb.append("  osArch:              ").append(sysProp("os.arch")).append('\n');

        sb.append("  insideDocker:        ").append(new File("/.dockerenv").exists()).append('\n');
        String selfCgroup = readFileOneLine("/proc/self/cgroup", 240);
        if (selfCgroup != null) {
            sb.append("  /proc/self/cgroup:   ").append(selfCgroup).append('\n');
        }
        String etcHostname = readFileOneLine("/etc/hostname", 120);
        if (etcHostname != null) {
            sb.append("  /etc/hostname:       ").append(etcHostname).append('\n');
        }

        sb.append("  javaProcessCount:    ").append(countJavaProcesses()).append('\n');

        sb.append("  env.FLOWTREE_ROOT_HOST:    ").append(env("FLOWTREE_ROOT_HOST")).append('\n');
        sb.append("  env.FLOWTREE_ROOT_PORT:    ").append(env("FLOWTREE_ROOT_PORT")).append('\n');
        sb.append("  env.FLOWTREE_WORKING_DIR:  ").append(env("FLOWTREE_WORKING_DIR")).append('\n');
        sb.append("  env.FLOWTREE_NODE_LABELS:  ").append(env("FLOWTREE_NODE_LABELS")).append('\n');
        sb.append("  env.FLOWTREE_NODE_ID:      ").append(env("FLOWTREE_NODE_ID")).append('\n');
        sb.append("  env.CLAUDE_CODE_OAUTH_TOKEN: ").append(envPresence("CLAUDE_CODE_OAUTH_TOKEN"))
                .append('\n');
        sb.append("  env.ANTHROPIC_API_KEY:     ").append(envPresence("ANTHROPIC_API_KEY")).append('\n');

        sb.append("==========================");
        return sb.toString();
    }

    /** Returns the local hostname, or {@code "unknown"} if it cannot be resolved. */
    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /** Returns the local IP address as a string, or {@code "unknown"} on failure. */
    private static String hostAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /** Returns the PID of this process's parent, or {@code "unknown"} if unavailable. */
    private static String parentPid() {
        try {
            return ProcessHandle.current().parent()
                    .map(ProcessHandle::pid)
                    .map(String::valueOf)
                    .orElse("unknown");
        } catch (SecurityException | UnsupportedOperationException e) {
            return "unknown";
        }
    }

    /** Returns the system property value or {@code <unset>} when null. */
    private static String sysProp(String key) {
        String v = System.getProperty(key);
        return v == null ? "<unset>" : v;
    }

    /** Returns the environment variable value or {@code <unset>} when null. */
    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "<unset>" : v;
    }

    /** Returns {@code <set>} or {@code <unset>} without disclosing the value. */
    private static String envPresence(String key) {
        return System.getenv(key) != null ? "<set>" : "<unset>";
    }

    /**
     * Reads a file, collapses newlines to {@code |}, trims, and clamps to
     * {@code limit} characters. Returns {@code null} on any I/O failure.
     */
    private static String readFileOneLine(String path, int limit) {
        try {
            String s = Files.readString(Paths.get(path));
            s = s.replace('\n', '|').trim();
            if (s.length() > limit) {
                s = s.substring(0, limit) + "...";
            }
            return s;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /**
     * Counts OS processes whose command path looks like a Java launcher.
     * Returns {@code -1} if the host OS or security manager forbids
     * process enumeration.
     */
    private static long countJavaProcesses() {
        try {
            return ProcessHandle.allProcesses()
                    .filter(p -> p.info().command()
                            .map(cmd -> cmd.endsWith("/java")
                                    || cmd.endsWith("java")
                                    || cmd.contains("/java "))
                            .orElse(false))
                    .count();
        } catch (SecurityException | UnsupportedOperationException e) {
            return -1L;
        }
    }
}
