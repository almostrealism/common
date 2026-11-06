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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A real git repository in a temporary directory, for tests that drive the
 * harness's git handling end to end and read the result back from git rather
 * than from the harness's own report.
 */
final class GitTestRepository implements AutoCloseable {

    /** The identity every commit in a test repository is made with. */
    static final String USER = "Recourse Test";

    /** The e-mail every commit in a test repository is made with. */
    static final String EMAIL = "recourse-test@example.com";

    /** The repository's directory. */
    private final Path dir;

    /**
     * Wraps an existing directory.
     *
     * @param dir the repository's directory
     */
    private GitTestRepository(Path dir) {
        this.dir = dir;
    }

    /**
     * Creates a repository on {@code master} with a test identity.
     *
     * @param prefix the temporary directory's name prefix
     * @param bare   whether to create a bare repository (a stand-in for a remote)
     * @return the repository
     * @throws IOException if the directory cannot be created or git fails
     * @throws InterruptedException if git is interrupted
     */
    static GitTestRepository create(String prefix, boolean bare) throws IOException, InterruptedException {
        GitTestRepository repo = new GitTestRepository(Files.createTempDirectory(prefix));
        if (bare) {
            repo.git("init", "--quiet", "--bare", "--initial-branch=master");
        } else {
            repo.git("init", "--quiet", "--initial-branch=master");
            repo.git("config", "user.email", EMAIL);
            repo.git("config", "user.name", USER);
            repo.git("config", "commit.gpgsign", "false");
        }
        return repo;
    }

    /**
     * Returns the repository's directory.
     *
     * @return the directory
     */
    Path dir() {
        return dir;
    }

    /**
     * Writes {@code content} to {@code path}, creating parent directories.
     *
     * @param path    the repository-relative path
     * @param content the file content
     * @throws IOException if the file cannot be written
     */
    void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Stages everything and commits it.
     *
     * @param message the commit message
     * @return the new commit's SHA
     * @throws IOException if git fails
     * @throws InterruptedException if git is interrupted
     */
    String commitAll(String message) throws IOException, InterruptedException {
        git("add", "-A");
        git("commit", "--quiet", "-m", message);
        return git("rev-parse", "HEAD").trim();
    }

    /**
     * Returns {@code path}'s content at {@code rev}, or {@code null} when the
     * path is absent there.
     *
     * @param rev  the revision
     * @param path the repository-relative path
     * @return the content, or {@code null}
     * @throws IOException if git cannot be started
     * @throws InterruptedException if git is interrupted
     */
    String show(String rev, String path) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "show", rev + ":" + path)
                .directory(dir.toFile()).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.getErrorStream().readAllBytes();
        return p.waitFor() == 0 ? out : null;
    }

    /**
     * Returns the parents of {@code rev}.
     *
     * @param rev the revision
     * @return the parent SHAs, first parent first
     * @throws IOException if git fails
     * @throws InterruptedException if git is interrupted
     */
    List<String> parents(String rev) throws IOException, InterruptedException {
        String[] fields = git("rev-list", "--parents", "-n", "1", rev).trim().split(" ");
        return List.of(fields).subList(1, fields.length);
    }

    /**
     * Returns whether {@code ref} resolves in this repository.
     *
     * @param ref the reference
     * @return {@code true} when it exists
     * @throws IOException if git cannot be started
     * @throws InterruptedException if git is interrupted
     */
    boolean hasRef(String ref) throws IOException, InterruptedException {
        return run("rev-parse", "--verify", "--quiet", ref).exitCode() == 0;
    }

    /**
     * Runs git and fails if it exits non-zero.
     *
     * @param args the git arguments
     * @return git's combined output
     * @throws IOException if git fails
     * @throws InterruptedException if git is interrupted
     */
    String git(String... args) throws IOException, InterruptedException {
        GitOperations.Result result = run(args);
        if (!result.succeeded()) {
            throw new IOException(result.describe());
        }
        return result.output();
    }

    /**
     * Runs git and returns its result whatever the exit code.
     *
     * @param args the git arguments
     * @return the result
     * @throws IOException if git cannot be started
     * @throws InterruptedException if git is interrupted
     */
    GitOperations.Result run(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitOperations.Result(List.of(args), p.waitFor(), out);
    }

    /** Deletes the repository's directory. */
    @Override
    public void close() throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
