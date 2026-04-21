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
import java.net.InetAddress;
import java.net.UnknownHostException;

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
     * Returns a compact, human-readable fingerprint of the current JVM
     * and its host. Safe to call at any time and never throws; fields
     * that cannot be resolved render as {@code unknown}.
     *
     * <p>The output is intentionally terse — four short lines with no
     * banners or field labels. When embedded in a message payload, the
     * message itself already provides the framing; a decorated block
     * would just be visual noise.</p>
     *
     * @return the fingerprint text, ready for logging or message posting
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(hostname()).append(" (").append(hostAddress()).append(") | ")
          .append(osName()).append(" on ").append(sysProp("os.arch"))
          .append(new File("/.dockerenv").exists() ? " (docker)" : " (no docker)").append('\n');
        sb.append(sysProp("user.name")).append(":[")
          .append(sysProp("user.dir")).append("]\n");
        sb.append("java ").append(sysProp("java.version"))
          .append(" (").append(sysProp("java.vendor")).append(")\n");
        sb.append("process ID ").append(ProcessHandle.current().pid())
          .append(" / ").append(parentPid());
        return sb.toString();
    }

    /** Returns a normalised OS name — Linux/macOS/Windows, falling through to the raw property otherwise. */
    private static String osName() {
        String raw = System.getProperty("os.name", "");
        String lower = raw.toLowerCase();
        if (lower.startsWith("mac")) return "macOS";
        if (lower.startsWith("linux")) return "Linux";
        if (lower.startsWith("windows")) return "Windows";
        return raw.isEmpty() ? "unknown" : raw;
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

    /** Returns the system property value or {@code unknown} when null. */
    private static String sysProp(String key) {
        String v = System.getProperty(key);
        return v == null ? "unknown" : v;
    }
}
