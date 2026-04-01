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

import io.flowtree.job.Job;
import org.almostrealism.io.ConsoleFeatures;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Comparator;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Abstract base class for jobs that require a managed execution
 * environment on the receiving node.
 *
 * <p>Currently handles Python virtual environment setup: when
 * {@link #pythonRequirements} is set, this class ensures a venv
 * exists at a known location with the specified packages installed.
 * The venv is reused across jobs and only reinstalled when the
 * requirements change (detected via SHA-256 hash).</p>
 *
 * <p>The venv python path is exposed via {@link #getPythonCommand()}
 * so that subclasses and MCP tool configuration can use it instead
 * of bare {@code python3}.</p>
 *
 * <h2>Wire format</h2>
 * <p>Requirements are base64-encoded under the key {@code pyReqs}
 * and decoded on the receiving node.</p>
 *
 * @author Michael Murray
 * @see GitManagedJob
 */
public abstract class EnvironmentManagedJob implements Job, ConsoleFeatures {

    /**
     * System property for overriding the venv base directory.
     * Defaults to {@code ~/.flowtree/venv}.
     */
    public static final String VENV_DIR_PROPERTY = "flowtree.venvDirectory";

    /**
     * Candidate Python executables tried in order when creating a new venv.
     * Higher-version interpreters are preferred so that packages requiring
     * Python ≥3.10 (e.g. {@code mcp}) can always be installed.
     */
    private static final String[] PYTHON_CANDIDATES = {
        "python3.13", "python3.12", "python3.11", "python3.10", "python3"
    };

    /** Stamp file inside the venv that records which Python created it. */
    private static final String PYTHON_STAMP = ".python-exe";

    /** Requirements text (pip requirements.txt format). */
    private String pythonRequirements;

    /** Resolved path to the venv directory after {@link #prepareEnvironment()}. */
    private Path venvPath;

    /**
     * Sets the Python package requirements (pip requirements.txt content).
     *
     * <p>When set, {@link #prepareEnvironment()} will create a virtual
     * environment and install these packages before the job's main
     * work begins.</p>
     *
     * @param requirements the requirements.txt content
     */
    public void setPythonRequirements(String requirements) {
        this.pythonRequirements = requirements;
    }

    /**
     * Returns the Python package requirements.
     *
     * @return the requirements.txt content, or null if not set
     */
    public String getPythonRequirements() {
        return pythonRequirements;
    }

    /**
     * Returns the path to the Python command in the managed venv.
     *
     * <p>If no requirements are set or the environment has not been
     * prepared, falls back to {@code python3}.</p>
     *
     * @return absolute path to the venv python, or {@code "python3"}
     */
    public String getPythonCommand() {
        if (venvPath != null) {
            Path python = venvPath.resolve("bin").resolve("python3");
            if (Files.exists(python)) {
                return python.toAbsolutePath().toString();
            }
        }
        return "python3";
    }

    /**
     * Prepares the execution environment on the receiving node.
     *
     * <p>When Python requirements are configured, this method:</p>
     * <ol>
     *   <li>Resolves the venv directory (system property, then default)</li>
     *   <li>Creates the venv if it does not exist</li>
     *   <li>Compares a SHA-256 hash of the requirements against the last
     *       installed set — skips pip install if unchanged</li>
     *   <li>Writes requirements to a temp file and runs pip install</li>
     *   <li>Records the hash for future comparisons</li>
     * </ol>
     *
     * @throws IOException if a process fails to execute
     * @throws InterruptedException if a process is interrupted
     */
    protected void prepareEnvironment() throws IOException, InterruptedException {
        // Auto-discover requirements from the working directory if not
        // explicitly set on the job
        if ((pythonRequirements == null || pythonRequirements.isEmpty())
                && getEnvironmentWorkingDirectory() != null) {
            Path reqFile = Path.of(getEnvironmentWorkingDirectory(),
                    "tools", "mcp", "requirements.txt");
            if (Files.exists(reqFile)) {
                pythonRequirements = Files.readString(reqFile, StandardCharsets.UTF_8);
                log("Auto-discovered Python requirements from " + reqFile);
            }
        }

        if (pythonRequirements == null || pythonRequirements.isEmpty()) {
            return;
        }

        venvPath = resolveVenvPath();
        Path hashFile = venvPath.resolve(".requirements-hash");
        Path stampFile = venvPath.resolve(PYTHON_STAMP);
        String currentHash = sha256(pythonRequirements);
        String pythonExe = findPython();

        boolean venvExists = Files.exists(venvPath.resolve("bin").resolve("python3"));

        // Recreate the venv if it was built with a different Python executable
        // (e.g. system upgraded from 3.9 → 3.11, or a higher version is now available)
        if (venvExists) {
            if (!Files.exists(stampFile)
                    || !pythonExe.equals(Files.readString(stampFile, StandardCharsets.UTF_8).trim())) {
                log("Python executable changed to " + pythonExe + " — recreating venv");
                deleteDirectory(venvPath);
                venvExists = false;
            }
        }

        // Check if venv exists and requirements are unchanged
        if (venvExists) {
            if (Files.exists(hashFile)) {
                String installedHash = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
                if (currentHash.equals(installedHash)) {
                    log("Python venv up to date at " + venvPath);
                    return;
                }
            }
            log("Python requirements changed — reinstalling");
        } else {
            log("Creating Python venv at " + venvPath + " using " + pythonExe);
            Files.createDirectories(venvPath.getParent());
            runProcess(pythonExe, "-m", "venv", venvPath.toAbsolutePath().toString());
            Files.writeString(stampFile, pythonExe, StandardCharsets.UTF_8);
        }

        // Write requirements to a temp file and install
        Path reqFile = Files.createTempFile("flowtree-reqs-", ".txt");
        try {
            Files.writeString(reqFile, pythonRequirements, StandardCharsets.UTF_8);

            String pip = venvPath.resolve("bin").resolve("pip3").toAbsolutePath().toString();
            runProcess(pip, "install", "--upgrade", "pip");
            runProcess(pip, "install", "--no-cache-dir", "-r", reqFile.toAbsolutePath().toString());

            // Record the hash so subsequent jobs skip reinstall
            Files.writeString(hashFile, currentHash, StandardCharsets.UTF_8);
            log("Python requirements installed successfully");
        } finally {
            Files.deleteIfExists(reqFile);
        }
    }

    /**
     * Returns the best Python executable available on the host.
     *
     * <p>Tries {@link #PYTHON_CANDIDATES} in order and returns the first
     * one that can be launched successfully.  Preferring higher versions
     * ensures packages with {@code Requires-Python >=3.10} (e.g. {@code mcp})
     * can always be installed.</p>
     *
     * @return a Python executable name or path
     */
    private String findPython() {
        for (String candidate : PYTHON_CANDIDATES) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().transferTo(OutputStream.nullOutputStream());
                if (p.waitFor() == 0) {
                    return candidate;
                }
            } catch (IOException | InterruptedException ignored) {
                // this candidate is not available — try the next one
            }
        }
        return "python3";
    }

    /**
     * Recursively deletes a directory tree.
     *
     * @param dir the root directory to delete
     * @throws IOException if a file cannot be deleted
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) { }
                });
        }
    }

    /**
     * Resolves the venv directory path.
     *
     * <p>Priority:</p>
     * <ol>
     *   <li>System property {@value #VENV_DIR_PROPERTY}</li>
     *   <li>{@code ~/.flowtree/venv}</li>
     * </ol>
     *
     * @return the resolved venv directory path
     */
    private Path resolveVenvPath() {
        String override = System.getProperty(VENV_DIR_PROPERTY);
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".flowtree", "venv");
    }

    /**
     * Runs an external process and logs its output.
     *
     * @param command the command and arguments
     * @throws IOException if the process fails to start
     * @throws InterruptedException if the process is interrupted
     */
    private void runProcess(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed (exit " + exitCode + "): "
                    + String.join(" ", command));
        }
    }

    /**
     * Computes the SHA-256 hash of a string.
     *
     * @param input the string to hash
     * @return hex-encoded hash
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Returns the working directory for environment discovery.
     *
     * <p>Subclasses should override to provide the resolved working
     * directory so that {@link #prepareEnvironment()} can auto-discover
     * requirements files.</p>
     *
     * @return the working directory path, or null if unknown
     */
    protected String getEnvironmentWorkingDirectory() {
        return null;
    }

    // ==================== Wire format helpers ====================

    /**
     * Encodes the Python requirements for wire transmission.
     *
     * @return encoded requirements fragment, or empty string
     */
    protected String encodeEnvironmentProperties() {
        if (pythonRequirements != null && !pythonRequirements.isEmpty()) {
            return "::pyReqs:=" + Base64.getEncoder()
                    .encodeToString(pythonRequirements.getBytes(StandardCharsets.UTF_8));
        }
        return "";
    }

    /**
     * Handles deserialization of environment-related properties.
     *
     * @param key the property key
     * @param value the property value
     * @return true if the key was handled
     */
    protected boolean setEnvironmentProperty(String key, String value) {
        if ("pyReqs".equals(key)) {
            this.pythonRequirements = new String(
                    Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            return true;
        }
        return false;
    }
}
