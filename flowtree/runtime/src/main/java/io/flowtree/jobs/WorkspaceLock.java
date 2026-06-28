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

import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Exclusive OS-level lock that serialises {@link GitManagedJob} instances
 * operating on the same repository working directory.
 *
 * <p>The lock file lives at {@code <parent>/.flowtree-locks/<repoName>.lock} —
 * deliberately <em>outside</em> the git working tree so that
 * {@code git stash --include-untracked} cannot unlink it mid-job. POSIX
 * advisory locks ({@link FileLock}) are keyed per inode, so an unlink-recreate
 * of the lock file would silently break the lock. On a shared filesystem the
 * lock also serialises sibling containers that bind-mount the same repository.</p>
 *
 * <p>{@link #acquire(String)} blocks until the lock is available; failures are
 * logged but never abort the job. {@link #release()} is idempotent and safe to
 * call from a {@code finally} block whether or not the lock was acquired.</p>
 *
 * @author Michael Murray
 * @see GitManagedJob
 */
class WorkspaceLock implements ConsoleFeatures {

    /** Directory (under the workspace parent) that holds the lock files. */
    private static final String LOCK_DIR = ".flowtree-locks";

    /** Task identifier used to attribute lock activity in log messages. */
    private final String taskId;

    /** Channel held open for the duration of the lock; closed by {@link #release()}. */
    private FileChannel channel;

    /** The held lock, or {@code null} when no lock is currently held. */
    private FileLock lock;

    /**
     * Creates a workspace lock for the given job.
     *
     * @param taskId the task identifier, used only for log attribution
     */
    WorkspaceLock(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Acquires the exclusive lock for the repository at {@code workspacePath},
     * blocking until it becomes available. Failures (including an unresolvable
     * parent directory or I/O errors) are logged and leave the job unlocked
     * rather than aborting it.
     *
     * @param workspacePath the git repository root to lock
     */
    void acquire(String workspacePath) {
        try {
            Path repoRoot = Paths.get(workspacePath);
            Path parentDir = repoRoot.getParent();
            if (parentDir == null) {
                warn("Cannot resolve parent of workspace " + workspacePath
                        + " -- workspace lock skipped");
                return;
            }
            Path repoNamePath = repoRoot.getFileName();
            String repoName = repoNamePath != null ? repoNamePath.toString() : "unknown";
            Path lockDir = parentDir.resolve(LOCK_DIR);
            Files.createDirectories(lockDir);
            Path lockFile = lockDir.resolve(repoName + ".lock");
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            String host = hostname();
            // TODO(review): bracket-label log pattern "[host] ..." violates BracketLabelInLog checkstyle rule;
            //   rewrite as log("Acquiring workspace lock=" + lockFile + " host=" + host + " job=" + taskId + ...)
            log("[" + host + "] Acquiring workspace lock: " + lockFile
                    + " (job=" + taskId + ", repo=" + repoName + ")");
            lock = channel.lock();
            log("[" + host + "] Workspace lock acquired: " + lockFile);
        } catch (IOException e) {
            warn("Failed to acquire workspace lock for " + workspacePath + ": " + e.getMessage());
        }
    }

    /**
     * Releases the lock and closes the backing channel. Safe to call when no
     * lock is held.
     */
    void release() {
        if (lock != null) {
            try {
                lock.release();
                // TODO(review): same BracketLabelInLog violation — rewrite without "[hostname]" prefix
                log("[" + hostname() + "] Workspace lock released (job=" + taskId + ")");
            } catch (IOException e) {
                warn("Failed to release workspace lock: " + e.getMessage());
            }
            lock = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                warn("Failed to close workspace lock channel: " + e.getMessage());
            }
            channel = null;
        }
    }

    /** Returns the local hostname for log diagnostics, or {@code "unknown"} on failure. */
    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
