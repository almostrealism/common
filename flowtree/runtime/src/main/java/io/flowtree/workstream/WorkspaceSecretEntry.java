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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Declares a single workspace-scoped secret available to agent workstreams.
 *
 * <p>Each entry maps a logical name to the JSON file on disk that holds
 * the secret payload. The file must exist and must be readable only by the
 * controller process (permissions {@code 0600}). The controller logs a
 * warning at startup when a declared file is world- or group-readable.</p>
 *
 * @author Michael Murray
 * @see WorkspaceEntry#getSecrets()
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
}
