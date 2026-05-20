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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * A handle to a {@code tmux} session running a single user command.
 *
 * <p>This class lets a {@link GitManagedJob} subclass start a subprocess
 * inside a real terminal (a tmux pane), inject keystrokes into that pane,
 * stream the pane's output line-by-line, wait for completion, and forcibly
 * terminate the session. Compared with a raw {@link ProcessBuilder} the
 * tmux backing gives the child process a controlling tty -- which makes
 * subprocesses that probe {@code isatty()} behave the way they would in a
 * developer's shell, and provides a stable channel for sending text mid-run.</p>
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 * try (TmuxSession session = TmuxSession.create()) {
 *     session.workingDirectory(new File("/tmp/work"))
 *            .environment("FOO", "bar")
 *            .start(List.of("my-command", "--flag"));
 *     try (BufferedReader r = session.captureOutput()) {
 *         String line;
 *         while ((line = r.readLine()) != null) {
 *             // do something with each line
 *         }
 *     }
 *     int exit = session.waitFor(-1);
 * }
 * }</pre>
 *
 * <h2>Output capture</h2>
 *
 * <p>Output is captured via {@code tmux pipe-pane} into a temporary log file.
 * {@link #captureOutput()} returns a {@link BufferedReader} that tails that
 * file: reads block until either more bytes are written by tmux or the
 * session terminates. The reader returns {@code -1}/{@code null} on EOF
 * once the session has ended and the trailing bytes have been drained.</p>
 *
 * <h2>Exit codes</h2>
 *
 * <p>The user-supplied command is wrapped in a small bash script that
 * captures {@code $?} into a temp file once the command returns; that file
 * is read by {@link #waitFor(long)}. A session that is killed externally
 * (for example by the inactivity monitor) yields exit code {@code -1}.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #start(List)} and {@link #close()} are synchronized.
 * {@link #captureOutput()} may be called concurrently with
 * {@link #sendText(String)} / {@link #sendLine(String)} / {@link #waitFor(long)}.</p>
 */
public final class TmuxSession implements AutoCloseable {

    /** Tmux executable name. Looked up on {@code PATH}. */
    private static final String TMUX = "tmux";

    /** Poll interval used by the tailing reader and {@link #waitFor(long)}. */
    private static final long POLL_INTERVAL_MS = 100L;

    /** Grace period after the session dies during which the tailing reader still drains buffered bytes. */
    private static final long DRAIN_GRACE_MS = 250L;

    /** Stable tmux session name; used as the {@code -t} target for every command. */
    private final String sessionName;

    /** Per-session temp directory holding the wrapper script, output log, and exit-code file. */
    private final Path tempDir;

    /** Pane output log populated by {@code tmux pipe-pane}. */
    private final Path outputLog;

    /** File the wrapper script writes {@code $?} to once the user command returns. */
    private final Path exitCodeFile;

    /** Generated bash wrapper that synchronizes with this JVM, runs the user command, and records its exit code. */
    private final Path wrapperScript;

    /** Synchronization channel name passed to {@code tmux wait-for} so pipe-pane is wired before the command starts. */
    private final String readySignal;

    /** Optional working directory; applied via {@code tmux new-session -c}. */
    private File workingDirectory;

    /** Environment overrides; each entry becomes a {@code tmux new-session -e KEY=VALUE} argument. */
    private final Map<String, String> environment = new LinkedHashMap<>();

    /** True once {@link #start(List)} has completed. */
    private volatile boolean started;

    /** True once {@link #close()} has been called. */
    private volatile boolean closed;

    /** Cached pane pid (PID of the bash wrapper) once {@link #start(List)} returns. */
    private volatile long panePid = -1L;

    /** Use {@link #create()} or {@link #create(String)}. */
    private TmuxSession(String sessionName) throws IOException {
        this.sessionName = sessionName;
        this.tempDir = Files.createTempDirectory("tmux-session-");
        this.outputLog = tempDir.resolve("output.log");
        this.exitCodeFile = tempDir.resolve("exit-code");
        this.wrapperScript = tempDir.resolve("wrapper.sh");
        Files.createFile(outputLog);
        this.readySignal = "tmux-ready-" + sessionName;

        // Best-effort cleanup of temp files when the JVM exits normally.
        tempDir.toFile().deleteOnExit();
        outputLog.toFile().deleteOnExit();
        exitCodeFile.toFile().deleteOnExit();
        wrapperScript.toFile().deleteOnExit();
    }

    /**
     * Creates a session with an auto-generated name (prefix {@code ar-}).
     *
     * @return a fresh, unstarted session
     * @throws IOException if temp files cannot be created
     */
    public static TmuxSession create() throws IOException {
        return create("ar-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Creates a session with the given name. The name must be acceptable to tmux
     * as a session identifier (no whitespace, periods, or colons).
     *
     * @param sessionName the tmux session name
     * @return a fresh, unstarted session
     * @throws IOException if temp files cannot be created
     */
    public static TmuxSession create(String sessionName) throws IOException {
        Objects.requireNonNull(sessionName, "sessionName");
        if (sessionName.isEmpty() || sessionName.contains(" ")
                || sessionName.contains(".") || sessionName.contains(":")) {
            throw new IllegalArgumentException(
                    "tmux session name must not contain whitespace, '.' or ':': " + sessionName);
        }
        return new TmuxSession(sessionName);
    }

    /** Returns the tmux session name. */
    public String getName() {
        return sessionName;
    }

    /**
     * Sets the working directory for the wrapped command. Must be called before {@link #start(List)}.
     *
     * @param dir working directory; may be {@code null} to inherit from tmux
     * @return this session for chaining
     */
    public TmuxSession workingDirectory(File dir) {
        ensureNotStarted();
        this.workingDirectory = dir;
        return this;
    }

    /**
     * Sets a single environment variable for the wrapped command. Must be called before {@link #start(List)}.
     *
     * @param key   variable name
     * @param value variable value
     * @return this session for chaining
     */
    public TmuxSession environment(String key, String value) {
        ensureNotStarted();
        environment.put(Objects.requireNonNull(key, "key"),
                        Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Merges the given environment map into this session's environment.
     * Must be called before {@link #start(List)}.
     *
     * @param env environment overrides; entries with null values are skipped
     * @return this session for chaining
     */
    public TmuxSession environment(Map<String, String> env) {
        ensureNotStarted();
        if (env != null) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    environment.put(e.getKey(), e.getValue());
                }
            }
        }
        return this;
    }

    /**
     * Starts the wrapped command inside a new detached tmux session.
     *
     * <p>The session is created with {@code pipe-pane} wired up before the
     * user command begins, so no output is lost.</p>
     *
     * @param command the command and its arguments
     * @throws IOException           if any tmux invocation fails
     * @throws IllegalStateException if {@link #start(List)} was already called
     */
    public synchronized void start(List<String> command) throws IOException {
        if (started) throw new IllegalStateException("session already started: " + sessionName);
        if (closed) throw new IllegalStateException("session already closed: " + sessionName);
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }

        writeWrapperScript(command);

        List<String> newSession = new ArrayList<>();
        newSession.add(TMUX);
        newSession.add("new-session");
        newSession.add("-d");
        newSession.add("-s");
        newSession.add(sessionName);
        for (Map.Entry<String, String> e : environment.entrySet()) {
            newSession.add("-e");
            newSession.add(e.getKey() + "=" + e.getValue());
        }
        if (workingDirectory != null) {
            newSession.add("-c");
            newSession.add(workingDirectory.getAbsolutePath());
        }
        newSession.add("bash");
        newSession.add(wrapperScript.toString());

        runOrFail(newSession);
        runOrFail(List.of(TMUX, "pipe-pane", "-t", target(),
                "cat >> " + shellQuote(outputLog.toString())));
        runOrFail(List.of(TMUX, "wait-for", "-S", readySignal));

        try {
            String pidStr = runCapture(List.of(TMUX, "display-message", "-t", target(),
                    "-p", "#{pane_pid}")).trim();
            if (!pidStr.isEmpty()) {
                panePid = Long.parseLong(pidStr);
            }
        } catch (Exception ignored) {
            // pane_pid lookup is best effort
        }

        started = true;
    }

    /**
     * Sends literal text to the session's pane without a trailing Enter.
     *
     * @param text text to inject
     * @throws IOException if the tmux command fails
     */
    public void sendText(String text) throws IOException {
        ensureStarted();
        Objects.requireNonNull(text, "text");
        runOrFail(List.of(TMUX, "send-keys", "-t", target(), "-l", text));
    }

    /**
     * Sends literal text to the session's pane followed by the Enter key.
     *
     * @param text text to inject
     * @throws IOException if the tmux command fails
     */
    public void sendLine(String text) throws IOException {
        ensureStarted();
        Objects.requireNonNull(text, "text");
        runOrFail(List.of(TMUX, "send-keys", "-t", target(), "-l", text));
        runOrFail(List.of(TMUX, "send-keys", "-t", target(), "Enter"));
    }

    /**
     * Returns a reader that streams the pane's captured output line by line.
     * Reads block while the session is alive and there is no new data; the
     * reader reports EOF once the session has terminated and any buffered
     * bytes from {@code pipe-pane} have been drained.
     *
     * <p>Multiple readers can be created but they share the same underlying
     * log file; each starts from the beginning of captured output.</p>
     *
     * @return a tailing reader over the pane's output
     * @throws IOException if the output log cannot be opened
     */
    public BufferedReader captureOutput() throws IOException {
        ensureStarted();
        return new BufferedReader(new InputStreamReader(
                new TailingInputStream(outputLog, this::isAlive),
                StandardCharsets.UTF_8));
    }

    /**
     * Blocks until the wrapped command exits and returns its exit code.
     *
     * @param timeoutMillis maximum time to wait, or a negative value to wait indefinitely
     * @return the wrapped command's exit code, or {@code -1} if the session
     *         was killed externally before writing one, or if the wait timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    public int waitFor(long timeoutMillis) throws InterruptedException {
        ensureStarted();
        long deadline = (timeoutMillis < 0) ? Long.MAX_VALUE
                                            : System.currentTimeMillis() + timeoutMillis;
        while (isAlive()) {
            if (System.currentTimeMillis() >= deadline) return -1;
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return readExitCode();
    }

    /** Returns true if the tmux session is still running. */
    public boolean isAlive() {
        if (!started || closed) return false;
        try {
            return runSilent(List.of(TMUX, "has-session", "-t", target())) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns the PID of the bash wrapper that runs the user command,
     * or {@code -1} if it could not be determined.
     */
    public long getPid() {
        return panePid;
    }

    /**
     * Kills the tmux session if it is still running. Idempotent.
     *
     * <p>{@code tmux kill-session} sends {@code SIGHUP} to the pane's
     * processes, which propagates to descendants. After {@link #close()},
     * {@link #isAlive()} returns false and {@link #captureOutput()} readers
     * will drain remaining bytes and then report EOF.</p>
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (started) {
            try {
                runSilent(List.of(TMUX, "kill-session", "-t", target()));
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // ---- internals ----

    /** Generates the bash wrapper that waits for pipe-pane wiring, runs the command, and records its exit code. */
    private void writeWrapperScript(List<String> command) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("tmux wait-for ").append(shellQuote(readySignal)).append("\n");
        boolean first = true;
        for (String arg : command) {
            if (!first) sb.append(' ');
            sb.append(shellQuote(arg));
            first = false;
        }
        sb.append('\n');
        sb.append("status=$?\n");
        sb.append("echo \"$status\" > ").append(shellQuote(exitCodeFile.toString())).append('\n');
        Files.writeString(wrapperScript, sb.toString());
        if (!wrapperScript.toFile().setExecutable(true, true)) {
            throw new IOException("Failed to mark wrapper script executable: " + wrapperScript);
        }
    }

    /** Reads the exit code written by the wrapper script, or {@code -1} if the file is missing or unparseable. */
    private int readExitCode() {
        try {
            if (!Files.exists(exitCodeFile)) return -1;
            String s = Files.readString(exitCodeFile, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? -1 : Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Throws {@link IllegalStateException} if the session has already been started. */
    private void ensureNotStarted() {
        if (started) throw new IllegalStateException("session already started: " + sessionName);
    }

    /** Throws {@link IllegalStateException} unless the session has been started and not yet closed. */
    private void ensureStarted() {
        if (!started) throw new IllegalStateException("session not started: " + sessionName);
        if (closed) throw new IllegalStateException("session closed: " + sessionName);
    }

    /**
     * Returns the tmux target spec for this session.
     *
     * <p>The bare session name is used (no {@code =} prefix) because tmux's
     * pane-targeted commands such as {@code pipe-pane} reject the exact-match
     * prefix form. Collisions are avoided by giving each session a UUID-derived
     * name in {@link #create()}.</p>
     */
    private String target() {
        return sessionName;
    }

    /** Runs a tmux/bash command, treating a non-zero exit code as an {@link IOException}. */
    private static void runOrFail(List<String> args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        int rc;
        try {
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            rc = p.waitFor();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            p.destroy();
            throw new IOException("Interrupted while running " + args.get(0), ie);
        }
        if (rc != 0) {
            throw new IOException("Command failed (exit " + rc + "): " + args + " -- " + out.trim());
        }
    }

    /** Runs a command and returns its merged stdout/stderr; does not throw on non-zero exit. */
    private static String runCapture(List<String> args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    /** Runs a command and returns its exit code; discards output. */
    private static int runSilent(List<String> args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        try {
            return p.waitFor();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            p.destroy();
            return -1;
        }
    }

    /** Wraps a string in single quotes, escaping any embedded single quotes the bash way. */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * An {@link InputStream} that reads from a growing file. When the
     * underlying file reaches EOF the stream blocks (polling) until either
     * new bytes are appended or the producer indicates it is no longer
     * running. After the producer dies the stream drains any final bytes
     * and then reports EOF.
     */
    private static final class TailingInputStream extends InputStream {

        /** Underlying file the producer keeps appending to. */
        private final RandomAccessFile raf;

        /** Reports whether the writer that fills {@link #raf} is still running. */
        private final BooleanSupplier producerAlive;

        /** Set true once {@link #close()} has been called; subsequent reads return EOF. */
        private volatile boolean closed;

        /** Opens the file for reading and starts at offset 0. */
        TailingInputStream(Path path, BooleanSupplier producerAlive) throws IOException {
            this.raf = new RandomAccessFile(path.toFile(), "r");
            this.producerAlive = producerAlive;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) return -1;
            if (len == 0) return 0;
            while (!closed) {
                int n = raf.read(b, off, len);
                if (n > 0) return n;
                if (!producerAlive.getAsBoolean()) {
                    return drainFinalBytes(b, off, len);
                }
                sleepQuietly(POLL_INTERVAL_MS);
            }
            return -1;
        }

        /** Reads any bytes that arrived just before the producer terminated, then reports EOF. */
        private int drainFinalBytes(byte[] b, int off, int len) throws IOException {
            long deadline = System.currentTimeMillis() + DRAIN_GRACE_MS;
            while (System.currentTimeMillis() < deadline) {
                int n = raf.read(b, off, len);
                if (n > 0) return n;
                sleepQuietly(25L);
            }
            int finalRead = raf.read(b, off, len);
            return finalRead > 0 ? finalRead : -1;
        }

        /** Sleeps for {@code ms} milliseconds, converting interrupts to {@link InterruptedIOException}. */
        private void sleepQuietly(long ms) throws InterruptedIOException {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            try {
                raf.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
