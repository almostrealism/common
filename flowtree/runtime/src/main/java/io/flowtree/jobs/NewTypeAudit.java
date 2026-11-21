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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.git.GitOperations;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
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
 * editing its working-tree copy; only when the base branch resolves and has
 * none (the branch that introduces it) is the working-tree copy used. A
 * repository with neither has no type audit. A search that cannot run (an
 * unresolvable base branch, a missing interpreter, a timeout, malformed output)
 * fails closed: every changed Java file is refused, and {@link #getFailure()}
 * reports why, which the job's completion status makes visible.</p>
 *
 * <p>A refused file never leaves its callers behind: every type the file
 * declares is withheld, and every other changed Java file that refers to a
 * withheld type is refused with it, transitively, so the harness never commits
 * code that refers to a type it withheld.</p>
 *
 * <p>Only types the working tree adds relative to {@code HEAD} are examined: a
 * type the harness has already committed passed this gate when it was
 * committed.</p>
 *
 * <p>Only a type the search found to duplicate a candidate is audited or
 * refused; one that merely resembles others is reported to the review. A
 * duplicate is settled when it genuinely builds on a candidate, or when an
 * audit session examined it and left the {@value NewTypeAssessment#VERDICT_KEPT}
 * verdict in {@value #LEDGER_PATH}. Audit outcomes are recorded in an
 * {@link ObjectOrientedReviewLedger} of the branch, keyed by a digest of the
 * type's source together with its ledger entry, as the audit session left
 * them. A verdict written into the ledger by any other session does not count,
 * and any later change to the type or to its justification is audited
 * again.</p>
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

    /** Recorded for a duplicate the audit session examined and did not keep. */
    static final String AUDITED = "AUDITED";

    /** Recorded for a duplicate the audit session examined and kept. */
    static final String KEPT = "KEPT";

    /** File name suffix of the branch ledger that holds the audit outcomes. */
    static final String LEDGER_SUFFIX = ".new-types.tsv";

    /** Upper bound on one scan; a cold index of a large repository takes seconds. */
    static final long SCAN_TIMEOUT_SECONDS = 300;

    /** Path fragment identifying a main-source Java file. */
    private static final String MAIN_SOURCE = "src/main/java/";

    /** Digest of a type's source and justification, keying its audit outcome. */
    private static final String DIGEST = "SHA-256";

    /** Parses the scanner's JSON output. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The job whose working tree this audit examines. */
    private final GitManagedJob job;

    /** The branch's record of audit outcomes; resolved on first use. */
    private ObjectOrientedReviewLedger outcomes;

    /** Why the last scan could not run, or {@code null} when it ran. */
    private String failure;

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
     *         when the repository has no scanner, or when the scan fails (see
     *         {@link #examine()}, which reports the failure)
     */
    List<NewTypeAssessment> scan() {
        return examine().assessments();
    }

    /**
     * Runs the type-equivalence search over every changed Java file in the
     * job's working tree: the main-source ones are assessed, and every one is
     * listed with the types it declares. A failure is recorded in the result,
     * so that {@link #refusals()} can refuse what the search could not
     * examine, and kept as {@link #getFailure()} for the job's outcome.
     *
     * <p>A failed {@code git status} query yields a failed scan rather than an
     * empty one, so it is never read as "no Java files to audit"; the throwing
     * {@link GitOperations#requireChangedFiles} is what distinguishes it from a
     * clean tree, and the commit path's own call then refuses the commit.</p>
     *
     * @return the scan; {@link Scan#none()} when no main-source file changed
     *         or the repository has no scanner, and a failed scan when the
     *         {@code git status} query itself could not run
     */
    Scan examine() {
        String workDir = job.getWorkingDirectory();
        if (workDir == null) return Scan.none();
        List<String> changed;
        try {
            changed = GitOperations.requireChangedFiles(workDir);
        } catch (IOException e) {
            job.warn("New-type audit could not list changed files: " + e.getMessage());
            return Scan.failed(List.of(), "git status query failed: " + e.getMessage());
        }
        List<String> files = new ArrayList<>();
        for (String file : changed) {
            if (file.endsWith(".java") && !GitOperations.isExcludedPath(file)
                    && new File(workDir, file).isFile()) {
                files.add(file);
            }
        }
        if (files.stream().noneMatch(NewTypeAudit::isMainSource)) return Scan.none();
        Scan scan = scan(new File(workDir), files);
        // TODO(review): failure is never cleared, so a transient failure followed by a successful scan still reports DEGRADED "no Java file staged".
        if (scan.failed()) {
            failure = scan.failure();
            job.warn("New-type scan failed: " + scan.failure());
        }
        return scan;
    }

    /**
     * Returns why a scan of this job could not run, or {@code null} when every
     * scan ran. A scan that did not run refused every changed Java file, so a
     * job reporting this did not commit its Java changes.
     *
     * @return the failure, or {@code null}
     */
    String getFailure() {
        return failure;
    }

    /**
     * Runs the scanner over {@code files}, using the base branch's copy of the
     * scanner and passing each file's {@code HEAD} copy when it has one.
     *
     * @param root  the repository root
     * @param files repository-relative Java files to examine
     * @return the scan, recording the failure when the scanner could not run
     */
    private Scan scan(File root, List<String> files) {
        FileStager.GitOperations git = job.asGitOperations();
        List<Path> temporary = new ArrayList<>();
        try {
            String program = scannerProgram(git, root);
            if (program == null) return Scan.none();
            Path script = Files.createTempFile("type_equivalence", ".py");
            temporary.add(script);
            Files.writeString(script, program, StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>(List.of(
                    "python3", script.toString(), "scan", "--root", root.getAbsolutePath()));
            for (String file : files) {
                String head = git.executeForStdoutOrNull("show", "HEAD:" + file);
                if (head == null) {
                    command.add(file);
                } else {
                    Path copy = Files.createTempFile("head", ".java");
                    temporary.add(copy);
                    Files.writeString(copy, head, StandardCharsets.UTF_8);
                    command.add(file + "::" + copy);
                }
            }
            return Scan.parse(run(command, root), files);
        } catch (IOException e) {
            return Scan.failed(files, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Scan.failed(files, "interrupted");
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
     * Returns the scanner program to run: the base branch's copy, or the
     * working-tree copy only when the base branch resolves and has none (the
     * branch that introduces the scanner).
     *
     * <p>The base tree is listed with {@code ls-tree}, which exits zero whether
     * or not the path is present (empty listing when absent), so a lookup that
     * exits non-zero is a failure rather than an absence and fails closed
     * rather than trusting the mutable working-tree copy.</p>
     *
     * @param git  git access
     * @param root the repository root
     * @return the program text, or {@code null} when the repository has no
     *         scanner at all (no type audit)
     * @throws IOException when the base branch cannot be read while a
     *                     working-tree scanner exists: running that copy would
     *                     let the audited session choose its own search
     * @throws InterruptedException when interrupted while waiting
     */
    private String scannerProgram(FileStager.GitOperations git, File root)
            throws IOException, InterruptedException {
        String baseBranch = job.getBaseBranch();
        String base = "origin/" + (baseBranch == null || baseBranch.isBlank() ? "master" : baseBranch);
        File local = new File(root, SCANNER_PATH);
        if (git.execute("rev-parse", "--verify", "--quiet", base + "^{commit}") != 0) {
            if (!local.isFile()) return null;
            throw new IOException("base branch " + base + " cannot be resolved, so the scanner"
                    + " cannot be read from it; the working-tree copy is not trusted");
        }
        String listing = git.executeForStdoutOrNull("ls-tree", "--name-only", base, "--", SCANNER_PATH);
        if (listing == null) {
            throw new IOException("cannot look up " + SCANNER_PATH + " in " + base
                    + "; the working-tree copy is not trusted");
        }
        if (!listing.isBlank()) {
            String spec = base + ":" + SCANNER_PATH;
            String program = git.executeForStdoutOrNull("show", spec);
            if (program == null || program.isBlank()) {
                throw new IOException("cannot read " + spec);
            }
            return program;
        }
        return local.isFile() ? Files.readString(local.toPath(), StandardCharsets.UTF_8) : null;
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
     * Returns the duplicates that do not build on a candidate and that no
     * audit session has examined in their present form.
     *
     * @return the types the type audit still has to examine
     */
    List<NewTypeAssessment> unaudited() {
        List<NewTypeAssessment> out = new ArrayList<>();
        for (NewTypeAssessment a : scan()) {
            if (a.isUnsettled() && outcome(a) == null) out.add(a);
        }
        return out;
    }

    /**
     * Records the outcome of an audit session that was given the types named
     * in {@code fqns}, from a scan of the working tree the session left:
     * {@value #KEPT} for a type whose ledger entry now carries the
     * {@value NewTypeAssessment#VERDICT_KEPT} verdict, {@value #AUDITED}
     * otherwise. A type the session folded in is no longer in the scan and
     * needs no record.
     *
     * @param fqns fully-qualified names of the types the session was given
     */
    void markAudited(Collection<String> fqns) {
        for (NewTypeAssessment a : examine().assessments()) {
            if (!fqns.contains(a.getFqn())) continue;
            outcomes().record(a.getFqn(), contentId(a), a.isKeptByAudit() ? KEPT : AUDITED);
        }
    }

    /**
     * Returns the recorded audit outcome for a type in its present form.
     *
     * @param a the assessment of the type
     * @return {@value #KEPT}, {@value #AUDITED}, or {@code null} when no audit
     *         session examined this form of the type
     */
    String outcome(NewTypeAssessment a) {
        return outcomes().verdict(a.getFqn(), contentId(a));
    }

    /**
     * Returns the digest that keys a type's audit outcome: its declaring
     * file's current text together with its ledger entry, so that an edit to
     * either is audited again.
     *
     * @param a the assessment of the type
     * @return the hexadecimal digest
     */
    private String contentId(NewTypeAssessment a) {
        String text = "";
        String workDir = job.getWorkingDirectory();
        if (workDir != null) {
            try {
                text = Files.readString(Path.of(workDir, a.getPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                text = "";
            }
        }
        String entry = a.getLedgerEntry() == null ? "" : a.getLedgerEntry();
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST)
                    .digest((text + "\u0000" + entry).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST + " is unavailable", e);
        }
    }

    /**
     * Returns the branch's record of audit outcomes, resolving it once.
     *
     * @return the ledger; over no file when the branch cannot be resolved
     */
    private ObjectOrientedReviewLedger outcomes() {
        if (outcomes == null) {
            outcomes = ObjectOrientedReviewLedger.forBranch(job, LEDGER_SUFFIX, Set.of(AUDITED, KEPT));
        }
        return outcomes;
    }

    /**
     * Returns the files the harness must not stage, each with the reason:
     * <ul>
     *   <li>every file declaring a duplicate that neither builds on a
     *       candidate nor was kept by an audit session;</li>
     *   <li>every changed Java file, when the search could not run;</li>
     *   <li>every other changed Java file that refers to a type declared in a
     *       refused file, transitively, so that no commit stages a caller
     *       without the type it calls.</li>
     * </ul>
     *
     * @return repository-relative path to refusal reason; empty when nothing is refused
     */
    Map<String, String> refusals() {
        return refusals(examine(), changedJavaSources());
    }

    /**
     * Returns the refusals {@link #refusals()} reports for a scan of the
     * working tree and the changed Java sources that may refer to the refused
     * types.
     *
     * @param scan    the scan result
     * @param sources repository-relative path to every changed Java source
     * @return repository-relative path to refusal reason
     */
    private Map<String, String> refusals(Scan scan, Map<String, JavaSourceFile> sources) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> withheld = new LinkedHashSet<>();
        if (scan.failed()) {
            for (String file : scan.files()) {
                out.put(file, "the new-type audit could not run (" + scan.failure()
                        + "); no changed Java file is staged until it does");
            }
        }
        for (NewTypeAssessment a : scan.assessments()) {
            if (!a.isUnsettled() || KEPT.equals(outcome(a))) continue;
            out.merge(a.getPath(), a.refusalReason(), (x, y) -> x + "; " + y);
            withheld.add(a.getFqn());
        }
        for (String file : out.keySet()) {
            withheld.addAll(scan.declaredBy(file, sources.get(file)));
        }
        withholdDependents(scan, out, withheld, sources);
        return out;
    }

    /**
     * Adds to {@code refused} every source that refers to a withheld type,
     * adding the types each newly refused file declares to {@code withheld},
     * until no further file is affected.
     *
     * @param scan     the scan, which knows the types each examined file declares
     * @param refused  path to refusal reason; extended in place
     * @param withheld fully-qualified names of the types that will not be staged; extended in place
     * @param sources  repository-relative path to every changed Java source
     */
    private static void withholdDependents(Scan scan, Map<String, String> refused,
                                           Set<String> withheld, Map<String, JavaSourceFile> sources) {
        boolean grew = !withheld.isEmpty();
        while (grew) {
            grew = false;
            for (Map.Entry<String, JavaSourceFile> source : sources.entrySet()) {
                if (refused.containsKey(source.getKey())) continue;
                String type = source.getValue().firstReferenced(withheld);
                if (type == null) continue;
                refused.put(source.getKey(), "refers to " + type
                        + ", a new type that is not staged; staging this file without it"
                        + " would commit code that does not compile");
                withheld.addAll(scan.declaredBy(source.getKey(), source.getValue()));
                grew = true;
            }
        }
    }

    /**
     * Returns every changed Java file in the job's working tree that is not
     * excluded from staging and still exists.
     *
     * @return repository-relative paths; empty when the job has no working directory
     */
    private List<String> changedJavaFiles() {
        List<String> out = new ArrayList<>();
        String workDir = job.getWorkingDirectory();
        if (workDir == null) return out;
        for (String file : GitOperations.getChangedFiles(workDir)) {
            if (file.endsWith(".java") && !GitOperations.isExcludedPath(file)
                    && new File(workDir, file).isFile()) {
                out.add(file);
            }
        }
        return out;
    }

    /**
     * Returns every changed Java source in the job's working tree: the files
     * that may refer to a refused type.
     *
     * @return repository-relative path to source
     */
    private Map<String, JavaSourceFile> changedJavaSources() {
        Map<String, JavaSourceFile> out = new LinkedHashMap<>();
        for (String file : changedJavaFiles()) {
            try {
                out.put(file, JavaSourceFile.read(job.getWorkingDirectory(), file));
            } catch (IOException e) {
                job.warn("Cannot read " + file + " for the new-type audit: " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * One run of the type-equivalence search over the working tree.
     *
     * @param assessments the added types that resemble existing ones
     * @param added       fully-qualified names of every type the changes add,
     *                    whether or not it resembles an existing one
     * @param files       the Java files the search examined, or was to examine
     * @param declared    repository-relative path of each examined file to the
     *                    fully-qualified names of every top-level type it declares
     * @param failure     why the search could not run, or {@code null} when it ran
     */
    record Scan(List<NewTypeAssessment> assessments, List<String> added,
                List<String> files, Map<String, List<String>> declared, String failure) {

        /** Returns the scan of a tree with nothing to examine, or with no scanner. */
        static Scan none() {
            return new Scan(List.of(), List.of(), List.of(), Map.of(), null);
        }

        /**
         * Returns the scan that could not run over {@code files}.
         *
         * @param files   the files the search was to examine
         * @param failure why it could not run
         * @return the failed scan
         */
        static Scan failed(List<String> files, String failure) {
            return new Scan(List.of(), List.of(), List.copyOf(files), Map.of(),
                    failure == null ? "unknown failure" : failure);
        }

        /**
         * Parses the JSON the scanner prints:
         * {@code {"types": [...], "added": [...], "declared": {...}}}.
         *
         * @param json  the scanner's standard output
         * @param files the files the search examined
         * @return the scan
         * @throws IOException when the text is not the scanner's JSON
         */
        static Scan parse(String json, List<String> files) throws IOException {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IOException("scanner output is not a JSON object");
            }
            JsonNode addedNode = root.get("added");
            if (addedNode == null || !addedNode.isArray()) {
                throw new IOException("scanner output has no \"added\" array");
            }
            List<String> added = new ArrayList<>();
            for (JsonNode fqn : addedNode) {
                if (!fqn.isTextual() || fqn.asText().isBlank()) {
                    throw new IOException("scanner output has a blank \"added\" entry");
                }
                added.add(fqn.asText());
            }
            Map<String, List<String>> declared = new LinkedHashMap<>();
            root.path("declared").fields().forEachRemaining(e -> {
                List<String> types = new ArrayList<>();
                for (JsonNode fqn : e.getValue()) types.add(fqn.asText());
                declared.put(e.getKey(), List.copyOf(types));
            });
            return new Scan(NewTypeAssessment.parseAll(root), List.copyOf(added),
                    List.copyOf(files), Map.copyOf(declared), null);
        }

        /**
         * Returns the fully-qualified names of the top-level types a file
         * declares: as the scanner parsed them when it examined the file, or
         * else the type the file is named for.
         *
         * @param path   the repository-relative path of the file
         * @param source the file's source, or {@code null} when it is not known
         * @return the type names; empty when none is known
         */
        List<String> declaredBy(String path, JavaSourceFile source) {
            List<String> types = declared.get(path);
            if (types != null && !types.isEmpty()) return types;
            return source == null ? List.of() : List.of(source.namedType());
        }

        /** Returns whether the search could not run. */
        boolean failed() {
            return failure != null;
        }
    }
}
