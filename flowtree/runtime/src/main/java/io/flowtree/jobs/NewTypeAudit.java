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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A job's audit of the new top-level Java types in its working tree that
 * resemble types the repository already has.
 *
 * <p>The rule it enforces: an agent must not add a type that re-implements a
 * concept the codebase already has; the problem is solved with the existing
 * abstraction, improved if necessary. The new-type hook blocks such a type at
 * the moment it is written, but a hook runs inside the session it polices. This
 * audit is the independent half: {@link DeduplicationRule} runs a separate
 * session over every type {@link #unaudited} returns, and
 * {@link FileStagingConfig#forJob} refuses to stage a type {@link #refusals}
 * names until that session has settled it.</p>
 *
 * <p>Candidates come from the same search the hook uses,
 * {@value #SCANNER_PATH}, run as a program. The copy run is the one on the
 * base branch, so the session being audited cannot weaken the search by
 * editing its working-tree copy; only when the base branch has none (the
 * branch that introduces it) is the working-tree copy used. A repository with
 * neither has no type audit.</p>
 *
 * <p>Only types the working tree adds relative to {@code HEAD} are examined: a
 * type the harness has already committed passed this gate when it was
 * committed.</p>
 *
 * <p>A type is settled when it extends, implements or delegates to a candidate,
 * or when this job's audit session examined it and recorded the
 * {@value NewTypeAssessment#VERDICT_KEPT} verdict in {@value #LEDGER_PATH}. A
 * verdict already in the ledger before this job's audit ran does not count:
 * the session that wrote the type could have written it too.</p>
 *
 * @author Michael Murray
 * @see NewTypeAssessment
 * @see DeduplicationRule
 */
class NewTypeAudit {

    /** Repository-relative path of the type-equivalence search program. */
    static final String SCANNER_PATH = ".claude/hooks/lib/type_equivalence.py";

    /** Repository-relative path of the tracked justification ledger. */
    static final String LEDGER_PATH = ".claude/new-type-ledger.json";

    /** Upper bound on one scan; a cold index of a large repository takes seconds. */
    static final long SCAN_TIMEOUT_SECONDS = 300;

    /** Path fragment identifying a main-source Java file. */
    private static final String MAIN_SOURCE = "src/main/java/";

    /** The job whose working tree this audit examines. */
    private final GitManagedJob job;

    /** Fully-qualified names of the types this job's audit session has examined. */
    private final Set<String> audited = new LinkedHashSet<>();

    /**
     * Creates the audit of {@code job}'s working tree.
     *
     * @param job the job whose new types are audited
     */
    NewTypeAudit(GitManagedJob job) {
        this.job = job;
    }

    /**
     * Returns whether {@code path} is a main-source Java file: the only kind
     * of file whose types this audit examines.
     *
     * @param path a repository-relative path
     * @return {@code true} for a {@code .java} file under {@code src/main/java/}
     */
    static boolean isMainSource(String path) {
        return path.endsWith(".java") && path.contains(MAIN_SOURCE);
    }

    /**
     * Assesses every new main-source type in the job's working tree that has
     * likely equivalents.
     *
     * @return the assessments; empty when nothing resembles an existing type,
     *         when the repository has no scanner, or when the scan fails (the
     *         failure is reported through the job's warning log)
     */
    List<NewTypeAssessment> scan() {
        String workDir = job.getWorkingDirectory();
        if (workDir == null) return List.of();
        List<String> files = new ArrayList<>();
        for (String file : GitOperations.getChangedFiles(workDir)) {
            if (isMainSource(file) && !GitOperations.isExcludedPath(file)
                    && new File(workDir, file).isFile()) {
                files.add(file);
            }
        }
        if (files.isEmpty()) return List.of();
        return scan(new File(workDir), files);
    }

    /**
     * Runs the scanner over {@code files}, preferring the base branch's copy
     * of the scanner and passing each file's {@code HEAD} copy when it has one.
     *
     * @param root  the repository root
     * @param files repository-relative main-source files to examine
     * @return the assessments; empty on any failure
     */
    private List<NewTypeAssessment> scan(File root, List<String> files) {
        FileStager.GitOperations git = job.asGitOperations();
        String baseBranch = job.getBaseBranch();
        List<Path> temporary = new ArrayList<>();
        try {
            String program = stdoutOrNull(git, "show", "origin/"
                    + (baseBranch == null || baseBranch.isBlank() ? "master" : baseBranch)
                    + ":" + SCANNER_PATH);
            if (program == null) {
                File local = new File(root, SCANNER_PATH);
                if (!local.isFile()) return List.of();
                program = Files.readString(local.toPath(), StandardCharsets.UTF_8);
            }
            Path script = Files.createTempFile("type_equivalence", ".py");
            temporary.add(script);
            Files.writeString(script, program, StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>(List.of(
                    "python3", script.toString(), "scan", "--root", root.getAbsolutePath()));
            for (String file : files) {
                String head = stdoutOrNull(git, "show", "HEAD:" + file);
                if (head == null) {
                    command.add(file);
                } else {
                    Path copy = Files.createTempFile("head", ".java");
                    temporary.add(copy);
                    Files.writeString(copy, head, StandardCharsets.UTF_8);
                    command.add(file + "::" + copy);
                }
            }
            return NewTypeAssessment.parseAll(run(command, root));
        } catch (IOException e) {
            job.warn("New-type scan failed: " + e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            for (Path p : temporary) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a leftover temporary file is harmless
                }
            }
        }
    }

    /**
     * Runs a git command and returns its standard output when it succeeds.
     *
     * @param git  git access
     * @param args the git arguments
     * @return the standard output, or {@code null} when the command failed
     * @throws IOException          when git cannot be started
     * @throws InterruptedException when interrupted while waiting
     */
    private static String stdoutOrNull(FileStager.GitOperations git, String... args)
            throws IOException, InterruptedException {
        return git.execute(args) == 0 ? git.executeForStdout(args) : null;
    }

    /**
     * Runs the scanner and returns its standard output.
     *
     * @param command the command line
     * @param root    the directory to run in
     * @return the standard output
     * @throws IOException          when the scanner cannot run, fails, or times out
     * @throws InterruptedException when interrupted while waiting
     */
    private static String run(List<String> command, File root)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile("type_equivalence", ".json");
        try {
            Process process = new ProcessBuilder(command).directory(root)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("scanner timed out after " + SCAN_TIMEOUT_SECONDS + "s");
            }
            if (process.exitValue() != 0) {
                throw new IOException("scanner exited with " + process.exitValue());
            }
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /**
     * Returns the assessed types that do not build on a candidate and that
     * this job's audit session has not examined yet.
     *
     * @return the types the type audit still has to examine
     */
    List<NewTypeAssessment> unaudited() {
        List<NewTypeAssessment> out = new ArrayList<>();
        for (NewTypeAssessment a : scan()) {
            if (!a.buildsOnCandidate() && !audited.contains(a.getFqn())) out.add(a);
        }
        return out;
    }

    /**
     * Records that this job's audit session examined {@code types}.
     *
     * @param types the types the session was given
     */
    void markAudited(Collection<NewTypeAssessment> types) {
        for (NewTypeAssessment a : types) audited.add(a.getFqn());
    }

    /**
     * Returns whether this job's audit session has examined the named type.
     *
     * @param fqn the type's fully-qualified name
     * @return {@code true} once {@link #markAudited} has recorded it
     */
    boolean isAudited(String fqn) {
        return audited.contains(fqn);
    }

    /**
     * Returns the files the harness must not stage, each with the reason: every
     * file declaring an assessed type that neither builds on a candidate nor
     * was kept by this job's audit session.
     *
     * @return repository-relative path to refusal reason; empty when nothing is refused
     */
    Map<String, String> refusals() {
        return refusals(scan());
    }

    /**
     * Returns the refusals {@link #refusals()} reports for an
     * already-scanned set of assessments.
     *
     * @param assessments the scan result
     * @return repository-relative path to refusal reason
     */
    Map<String, String> refusals(List<NewTypeAssessment> assessments) {
        // TODO(review): refusing only the type's file still stages its callers (uncompilable commit).
        Map<String, String> out = new LinkedHashMap<>();
        for (NewTypeAssessment a : assessments) {
            if (a.buildsOnCandidate()) continue;
            if (isAudited(a.getFqn()) && a.isKeptByAudit()) continue;
            out.merge(a.getPath(), a.refusalReason(), (x, y) -> x + "; " + y);
        }
        return out;
    }
}
