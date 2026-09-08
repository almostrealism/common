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

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a workstream identifier to the {@link WorkstreamMailbox} that
 * carries its conversation, creating one on first use.
 *
 * <p>Mailboxes are persisted under a single directory, one NDJSON file per
 * workstream, and are loaded lazily: a controller that has never seen a
 * workstream's conversation pays nothing for it. Once loaded a mailbox stays
 * resident, because its monitor is what readers block on.</p>
 *
 * <p>A registry constructed without a directory keeps its mailboxes in memory
 * only. That is the right behaviour for a deployment with no data directory
 * and for tests, and it is a degradation rather than a failure: conversation
 * still works, it just does not survive a restart.</p>
 *
 * @author Michael Murray
 * @see WorkstreamMailbox
 */
public class MailboxRegistry {

    /** Name of the subdirectory holding one NDJSON file per workstream. */
    public static final String DIRECTORY_NAME = "mailbox";

    /** Directory holding the per-workstream files, or {@code null} when memory-only. */
    private final File directory;

    /** Loaded mailboxes, keyed by workstream identifier. */
    private final Map<String, WorkstreamMailbox> mailboxes;

    /**
     * Constructs a memory-only registry whose conversations do not survive a
     * restart.
     */
    public MailboxRegistry() {
        this(null);
    }

    /**
     * Constructs a registry that persists conversations under {@code directory}.
     *
     * @param directory the directory to hold per-workstream files, or
     *                  {@code null} to keep mailboxes in memory only
     */
    public MailboxRegistry(File directory) {
        this.directory = directory;
        this.mailboxes = new ConcurrentHashMap<>();
    }

    /**
     * Returns the directory conversations are persisted under.
     *
     * @return the directory, or {@code null} when this registry is memory-only
     */
    public File getDirectory() { return directory; }

    /**
     * Returns the mailbox for {@code workstreamId}, creating and loading it if
     * this is the first time it has been asked for.
     *
     * @param workstreamId identifier of the workstream
     * @return the mailbox; never {@code null}
     */
    public WorkstreamMailbox mailboxFor(String workstreamId) {
        return mailboxes.computeIfAbsent(workstreamId, id ->
                new WorkstreamMailbox(id, directory == null ? null : new File(directory, id + ".ndjson")));
    }
}
