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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates all git and general subprocess execution for jobs that
 * need to interact with a git repository.
 *
 * <p>This class extracts the process execution concerns from
 * {@link GitManagedJob}, providing a reusable interface for running
 * git commands, querying branch state, and cloning repositories.
 * Every {@link ProcessBuilder} created by this class is configured
 * with:</p>
 * <ul>
 *   <li>The working directory supplied at construction time</li>
 *   <li>A non-interactive SSH command
 *       ({@code ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes})</li>
 *   <li>Git identity environment variables ({@code GIT_AUTHOR_NAME},
 *       {@code GIT_COMMITTER_NAME}, {@code GIT_AUTHOR_EMAIL},
 *       {@code GIT_COMMITTER_EMAIL}) when a git identity is provided</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * GitOperations git = new GitOperations("/path/to/repo", "task-42");
 * git.setGitUserName("bot");
 * git.setGitUserEmail("bot@example.com");
 *
 * int exitCode = git.execute("status", "--porcelain");
 * String branch = git.getCurrentBranch();
 * boolean exists = git.branchExists("feature/foo");
 * }</pre>
 *
 * @author Michael Murray
 * @see GitManagedJob
 */
public class GitOperations implements ConsoleFeatures {

    /** SSH command used to prevent interactive prompts in headless environments. */
    private static final String SSH_COMMAND =
            "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes";

    /**
     * Well-known paths where git may be installed on macOS and Linux.
     * Checked in order when the bare {@code "git"} command cannot be found
     * on the inherited {@code PATH}.
     */
    private static final String[] GIT_SEARCH_PATHS = {
            "/usr/local/bin/git",
            "/opt/homebrew/bin/git",
            "/usr/bin/git"
    };

    /** Resolved git command, cached after first lookup. */
    private static volatile String resolvedGitCommand;

    /**
     * Directories that should be present on {@code PATH} for subprocess
     * execution. On macOS, when the JVM is launched from a non-login
     * context (LaunchAgent, daemon, container entry point), the inherited
     * {@code PATH} may be minimal. This list covers the standard system
     * directories plus common installation targets for tools like
     * {@code git}, {@code claude}, {@code gh}, and {@code node}.
     *
     * <p>The user home {@code ~/.local/bin} entry is resolved at runtime
     * via {@link #EXTRA_PATH_HOME_LOCAL}.</p>
     */
    private static final String[] EXTRA_PATH_DIRS = {
            "/bin",
            "/usr/bin",
            "/usr/sbin",
            "/usr/local/bin",
            "/opt/homebrew/bin"
    };

    /** {@code ~/.local/bin} — resolved once from the {@code user.home} property. */
    private static final String EXTRA_PATH_HOME_LOCAL =
            System.getProperty("user.home") + File.separator + ".local" + File.separator + "bin";

    private final String workingDirectory;
    private final String taskId;

    private String gitUserName;
    private String gitUserEmail;

    /**
     * Creates a new {@code GitOperations} instance.
     *
     * @param workingDirectory the directory in which git commands will be
     *                         executed; may be {@code null} to use the JVM's
     *                         current working directory
     * @param taskId           an identifier used in log message formatting;
     *                         may be {@code null}
     */
    public GitOperations(String workingDirectory, String taskId) {
        this.workingDirectory = workingDirectory;
        this.taskId = taskId;
    }

    /**
     * Sets the git user name applied to all git process environments.
     *
     * @param gitUserName the name to set as {@code GIT_AUTHOR_NAME} and
     *                    {@code GIT_COMMITTER_NAME}
     */
    public void setGitUserName(String gitUserName) {
        this.gitUserName = gitUserName;
    }

    /**
     * Sets the git user email applied to all git process environments.
     *
     * @param gitUserEmail the email to set as {@code GIT_AUTHOR_EMAIL} and
     *                     {@code GIT_COMMITTER_EMAIL}
     */
    public void setGitUserEmail(String gitUserEmail) {
        this.gitUserEmail = gitUserEmail;
    }

    /**
     * Returns the git user name, or {@code null} if not set.
     *
     * @return the git user name
     */
    public String getGitUserName() {
        return gitUserName;
    }

    /**
     * Returns the git user email, or {@code null} if not set.
     *
     * @return the git user email
     */
    public String getGitUserEmail() {
        return gitUserEmail;
    }

    /**
     * Executes a git command and returns the process exit code.
     *
     * <p>The command is constructed as {@code git <args>}. Standard error
     * is merged into standard output. If the process exits with a non-zero
     * code, the combined output is logged as a warning.</p>
     *
     * @param args the git sub-command and its arguments
     *             (e.g., {@code "status", "--porcelain"})
     * @return the process exit code (0 indicates success)
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted
     *                              while waiting for the process
     */
    public int execute(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolveGitCommand());
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        configureGitProcess(pb);

        Process process = pb.start();
        String output = readProcessOutput(process);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            warn("git " + String.join(" ", args)
                    + " failed (exit " + exitCode + "): " + output.trim());
        }

        return exitCode;
    }

    /**
     * Executes a git command and returns the standard output.
     *
     * <p>The command is constructed as {@code git <args>}. Standard error
     * is merged into standard output. The full output is returned
     * regardless of the exit code.</p>
     *
     * @param args the git sub-command and its arguments
     * @return the combined standard output and standard error
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted
     *                              while waiting for the process
     */
    public String executeWithOutput(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolveGitCommand());
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        configureGitProcess(pb);

        Process process = pb.start();
        String output = readProcessOutput(process);

        process.waitFor();
        return output;
    }

    /**
     * Returns the name of the currently checked-out branch.
     *
     * @return the current branch name (e.g., {@code "master"})
     * @throws IOException          if the git process cannot be started
     * @throws InterruptedException if the current thread is interrupted
     */
    public String getCurrentBranch() throws IOException, InterruptedException {
        return executeWithOutput("rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    /**
     * Checks whether a branch exists locally or on the remote {@code origin}.
     *
     * <p>The check first looks for a local ref at
     * {@code refs/heads/<branch>}, then for a remote ref at
     * {@code refs/remotes/origin/<branch>}.</p>
     *
     * @param branch the branch name to look up
     * @return {@code true} if the branch exists locally or on origin
     * @throws IOException          if the git process cannot be started
     * @throws InterruptedException if the current thread is interrupted
     */
    public boolean branchExists(String branch) throws IOException, InterruptedException {
        int result = execute("show-ref", "--verify", "--quiet",
                "refs/heads/" + branch);
        if (result == 0) return true;

        result = execute("show-ref", "--verify", "--quiet",
                "refs/remotes/origin/" + branch);
        return result == 0;
    }

    /**
     * Clones a git repository into the specified target path.
     *
     * <p>Parent directories are created as needed. The clone process
     * is configured with the non-interactive SSH command but does not
     * apply git identity variables (they are not relevant for clone).</p>
     *
     * @param repoUrl    the remote repository URL
     *                   (e.g., {@code "https://github.com/owner/repo.git"})
     * @param targetPath the local directory to clone into
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted
     * @throws RuntimeException     if the clone exits with a non-zero code
     */
    public void cloneRepository(String repoUrl, String targetPath)
            throws IOException, InterruptedException {
        File targetDir = new File(targetPath);
        File parentDir = targetDir.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        log("Cloning " + repoUrl + " into " + targetPath + "...");

        List<String> command = new ArrayList<>();
        command.add(resolveGitCommand());
        command.add("clone");
        command.add(repoUrl);
        command.add(targetPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.environment().put("GIT_SSH_COMMAND", SSH_COMMAND);
        augmentPath(pb);

        Process process = pb.start();
        String output = readProcessOutput(process);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Failed to clone " + repoUrl + " (exit " + exitCode + "): "
                            + output.trim());
        }

        log("Clone completed successfully");
    }

    /**
     * Executes an arbitrary (non-git) command and returns the exit code.
     *
     * <p>The working directory is set to the directory supplied at
     * construction time. Standard error is merged into standard output.
     * If the process exits with a non-zero code, the output is logged
     * as a warning.</p>
     *
     * @param command the command and its arguments
     *                (e.g., {@code "gh", "pr", "list"})
     * @return the process exit code
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted
     */
    public int executeCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        configureProcess(pb);

        Process process = pb.start();
        String output = readProcessOutput(process);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            warn(String.join(" ", command)
                    + " failed (exit " + exitCode + "): " + output.trim());
        }

        return exitCode;
    }

    /**
     * Executes an arbitrary (non-git) command and returns the standard output.
     *
     * <p>The working directory is set to the directory supplied at
     * construction time. Standard error is merged into standard output.</p>
     *
     * @param command the command and its arguments
     * @return the combined standard output and standard error
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted
     */
    public String executeCommandWithOutput(String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        configureProcess(pb);

        Process process = pb.start();
        String output = readProcessOutput(process);

        process.waitFor();
        return output;
    }

    /** {@inheritDoc} */
    @Override
    public String formatMessage(String msg) {
        if (taskId != null && !taskId.isEmpty()) {
            return "GitOperations [" + taskId + "]: " + msg;
        }
        return "GitOperations: " + msg;
    }

    /**
     * Resolves the absolute path to the {@code git} executable.
     *
     * <p>On macOS, when the JVM is launched from a non-login context
     * (LaunchAgent, daemon, or IDE), the inherited {@code PATH} may not
     * include directories like {@code /usr/local/bin} or
     * {@code /opt/homebrew/bin}. This method first attempts to use the
     * bare {@code "git"} command; if that fails, it probes
     * {@link #GIT_SEARCH_PATHS well-known installation paths}. The result
     * is cached for the lifetime of the JVM.</p>
     *
     * @return the path to a usable git executable (never {@code null})
     */
    public static String resolveGitCommand() {
        String cached = resolvedGitCommand;
        if (cached != null) {
            return cached;
        }

        // Try bare "git" first — works when PATH is correctly inherited
        try {
            Process probe = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            probe.getInputStream().readAllBytes();
            if (probe.waitFor() == 0) {
                resolvedGitCommand = "git";
                return "git";
            }
        } catch (IOException | InterruptedException ignored) {
            // bare "git" not on PATH — fall through to search
        }

        for (String path : GIT_SEARCH_PATHS) {
            File candidate = new File(path);
            if (candidate.isFile() && candidate.canExecute()) {
                resolvedGitCommand = path;
                return path;
            }
        }

        // Last resort — return bare "git" and let the caller surface the error
        resolvedGitCommand = "git";
        return "git";
    }

    /**
     * Ensures that a {@link ProcessBuilder}'s {@code PATH} environment
     * variable includes all directories required for subprocess execution.
     *
     * <p>On macOS, JVM processes launched from non-login contexts inherit
     * a minimal {@code PATH} that may exclude directories like
     * {@code /usr/local/bin}, {@code /opt/homebrew/bin}, or
     * {@code ~/.local/bin}. This method inspects the current {@code PATH},
     * appends any missing directories from a well-known set, and writes
     * the result back into the process environment. Directories that do
     * not exist on disk are skipped.</p>
     *
     * <p>This method is idempotent — calling it multiple times on the
     * same {@link ProcessBuilder} has no additional effect.</p>
     *
     * @param pb the process builder whose environment will be augmented
     */
    public static void augmentPath(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        String currentPath = env.getOrDefault("PATH", "");
        Set<String> present = new LinkedHashSet<>(Arrays.asList(currentPath.split(File.pathSeparator)));

        StringBuilder augmented = new StringBuilder(currentPath);
        String[] allDirs = new String[EXTRA_PATH_DIRS.length + 1];
        System.arraycopy(EXTRA_PATH_DIRS, 0, allDirs, 0, EXTRA_PATH_DIRS.length);
        allDirs[allDirs.length - 1] = EXTRA_PATH_HOME_LOCAL;

        for (String dir : allDirs) {
            if (!present.contains(dir) && new File(dir).isDirectory()) {
                augmented.append(File.pathSeparator).append(dir);
                present.add(dir);
            }
        }

        env.put("PATH", augmented.toString());
    }

    /**
     * Configures a {@link ProcessBuilder} for git command execution.
     *
     * <p>Sets the working directory, merges stderr into stdout, applies
     * the non-interactive SSH command, and injects git identity
     * environment variables when they have been provided.</p>
     *
     * @param pb the process builder to configure
     */
    private void configureGitProcess(ProcessBuilder pb) {
        configureProcess(pb);
        pb.environment().put("GIT_SSH_COMMAND", SSH_COMMAND);
        applyGitIdentity(pb);
    }

    /**
     * Configures a {@link ProcessBuilder} for general command execution.
     *
     * <p>Sets the working directory, merges stderr into stdout, and
     * augments the {@code PATH} so that tools installed in common
     * locations are reachable.</p>
     *
     * @param pb the process builder to configure
     */
    private void configureProcess(ProcessBuilder pb) {
        if (workingDirectory != null) {
            pb.directory(new File(workingDirectory));
        }
        pb.redirectErrorStream(true);
        augmentPath(pb);
    }

    /**
     * Applies git identity environment variables to a {@link ProcessBuilder}.
     *
     * <p>Sets {@code GIT_AUTHOR_NAME}, {@code GIT_COMMITTER_NAME},
     * {@code GIT_AUTHOR_EMAIL}, and {@code GIT_COMMITTER_EMAIL} so the
     * identity is scoped to the process and never persisted in the
     * repository's local config.</p>
     *
     * @param pb the process builder to configure
     */
    private void applyGitIdentity(ProcessBuilder pb) {
        if (gitUserName != null && !gitUserName.isEmpty()) {
            pb.environment().put("GIT_AUTHOR_NAME", gitUserName);
            pb.environment().put("GIT_COMMITTER_NAME", gitUserName);
        }
        if (gitUserEmail != null && !gitUserEmail.isEmpty()) {
            pb.environment().put("GIT_AUTHOR_EMAIL", gitUserEmail);
            pb.environment().put("GIT_COMMITTER_EMAIL", gitUserEmail);
        }
    }

    /**
     * Reads all output from a process's input stream into a string.
     *
     * @param process the process to read from
     * @return the full output, with lines separated by newlines
     * @throws IOException if reading fails
     */
    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }
}
