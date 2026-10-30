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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Declares a single workspace-scoped secret available to agent workstreams.
 *
 * <p>Each entry maps a logical name to the JSON file on disk that holds
 * the secret payload. The file must exist and must be readable only by the
 * controller process (permissions {@code 0600}). The controller logs a
 * warning at startup when a declared file is world- or group-readable.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkspaceSecretEntry {
    /** Unique name within the workspace; URL-safe (lowercase letters, digits, hyphens). */
    private String name;
    /** Absolute path to the JSON payload file on disk. */
    private String file;

    /** Returns the secret name. */
    public String getName() { return name; }
    /** Sets the secret name. */
    public void setName(String name) { this.name = name; }

    /** Returns the absolute path to the JSON payload file. */
    public String getFile() { return file; }
    /** Sets the path to the JSON payload file. */
    public void setFile(String file) { this.file = file; }

    /**
     * Describes what is wrong with this secret's payload file, or returns
     * {@code null} when nothing is: the file is missing, or its permissions
     * are wider than {@code 0600} (readable or writable by group or others).
     * A filesystem without POSIX permissions reports no problem.
     *
     * @return a one-line description of the problem, or {@code null}
     */
    public String filePermissionProblem() {
        if (file == null) return null;
        Path filePath = Path.of(file);
        if (!Files.exists(filePath)) {
            return "Secret file not found: " + file;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(filePath);
            boolean tooPermissive = perms.contains(PosixFilePermission.GROUP_READ)
                    || perms.contains(PosixFilePermission.GROUP_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_READ)
                    || perms.contains(PosixFilePermission.OTHERS_WRITE);
            return tooPermissive ? "Secret file has unsafe permissions (expected 0600): " + file : null;
        } catch (UnsupportedOperationException e) {
            return null;
        } catch (IOException e) {
            return "Could not check permissions for secret file " + file + ": " + e.getMessage();
        }
    }
}
