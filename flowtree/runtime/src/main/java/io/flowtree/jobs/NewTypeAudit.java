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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * fails closed: every file it was to examine is refused.</p>
 *
 * <p>A refused file never leaves its callers behind: every other changed Java
 * file that refers to a type in a refused file is refused with it, so the
 * harness never commits code that refers to a type it withheld.</p>
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

    /** A Java source's package declaration. */
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /** Parses the scanner's JSON output. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     *         when the repository has no scanner, or when the scan fails (see
     *         {@link #examine()}, which reports the failure)
     */
    List<NewTypeAssessment> scan() {
        return examine().assessments();
    }

    /**
     * Runs the type-equivalence search over every changed main-source file in
     * the job's working tree. A failure is reported through the job's warning
     * log and recorded in the result, so that {@link #refusals()} can refuse
     * what the search could not examine.
     *
     * @return the scan; {@link Scan#none()} when there is nothing to examine
     *         or the repository has no scanner
     */
    Scan examine() {
        String workDir = job.getWorkingDirectory();
        if (workDir == null) return Scan.none();
        List<String> files = new ArrayList<>();
        for (String file : GitOperations.getChangedFiles(workDir)) {
            if (isMainSource(file) && !GitOperations.isExcludedPath(file)
                    && new File(workDir, file).isFile()) {
                files.add(file);
            }
        }
        if (files.isEmpty()) return Scan.none();
        Scan scan = scan(new File(workDir), files);
        if (scan.failed()) job.warn("New-type scan failed: " + scan.failure());
        return scan;
    }

    /**
     * Runs the scanner over {@code files}, using the base branch's copy of the
     * scanner and passing each file's {@code HEAD} copy when it has one.
     *
     * @param root  the repository root
     * @param files repository-relative main-source files to examine
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
        String spec = base + ":" + SCANNER_PATH;
        if (git.execute("cat-file", "-e", spec) == 0) {
            String program = stdoutOrNull(git, "show", spec);
            if (program == null || program.isBlank()) {
                throw new IOException("cannot read " + spec);
            }
            return program;
        }
        return local.isFile() ? Files.readString(local.toPath(), StandardCharsets.UTF_8) : null;
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
     * Returns the files the harness must not stage, each with the reason:
     * <ul>
     *   <li>every file declaring an assessed type that neither builds on a
     *       candidate nor was kept by this job's audit session;</li>
     *   <li>every file the search was to examine, when it could not run;</li>
     *   <li>every other changed Java file that refers to a type in a refused
     *       file, transitively, so that no commit stages a caller without the
     *       type it calls.</li>
     * </ul>
     *
     * @return repository-relative path to refusal reason; empty when nothing is refused
     */
    Map<String, String> refusals() {
        return refusals(examine(), changedJavaSources());
    }

    /**
     * Returns the refusals {@link #refusals()} reports for an already-run scan
     * and the changed Java sources that may refer to the refused types.
     *
     * @param scan    the scan result
     * @param sources repository-relative path to text of every changed Java file
     * @return repository-relative path to refusal reason
     */
    Map<String, String> refusals(Scan scan, Map<String, String> sources) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> withheld = new LinkedHashSet<>();
        if (scan.failed()) {
            for (String file : scan.files()) {
                out.put(file, "the new-type audit could not run (" + scan.failure()
                        + "); no changed main-source file is staged until it does");
            }
        }
        for (NewTypeAssessment a : scan.assessments()) {
            if (a.buildsOnCandidate()) continue;
            if (isAudited(a.getFqn()) && a.isKeptByAudit()) continue;
            out.merge(a.getPath(), a.refusalReason(), (x, y) -> x + "; " + y);
            withheld.add(a.getFqn());
        }
        // TODO(review): only the file-named type is withheld; secondary top-level types in a refused file are not.
        for (String file : out.keySet()) {
            String declared = declaredType(file, sources.get(file));
            if (declared != null) withheld.add(declared);
        }
        withholdDependents(out, withheld, sources);
        return out;
    }

    /**
     * Adds to {@code refused} every source that refers to a withheld type,
     * adding the type each newly refused file declares to {@code withheld},
     * until no further file is affected.
     *
     * @param refused  path to refusal reason; extended in place
     * @param withheld fully-qualified names of the types that will not be staged; extended in place
     * @param sources  repository-relative path to text of every changed Java file
     */
    private static void withholdDependents(Map<String, String> refused, Set<String> withheld,
                                           Map<String, String> sources) {
        boolean grew = !withheld.isEmpty();
        while (grew) {
            grew = false;
            for (Map.Entry<String, String> source : sources.entrySet()) {
                if (refused.containsKey(source.getKey())) continue;
                String type = firstReferenced(source.getValue(), withheld);
                if (type == null) continue;
                refused.put(source.getKey(), "refers to " + type
                        + ", a new type that is not staged; staging this file without it"
                        + " would commit code that does not compile");
                String declared = declaredType(source.getKey(), source.getValue());
                if (declared != null) withheld.add(declared);
                grew = true;
            }
        }
    }

    /**
     * Returns the first of {@code types} that {@code source} refers to: by its
     * fully-qualified name (an import or a qualified use), or by its simple
     * name when {@code source} is in the same package or imports that package
     * whole. A mention in a comment counts; withholding one file too many is
     * safe, staging one too many is not.
     *
     * @param source the Java source text
     * @param types  fully-qualified type names
     * @return the first referenced type, or {@code null}
     */
    static String firstReferenced(String source, Collection<String> types) {
        String pkg = packageOf(source);
        for (String fqn : types) {
            if (Pattern.compile("(?<![\\w.$])" + Pattern.quote(fqn) + "(?![\\w$])").matcher(source).find()) {
                return fqn;
            }
            int dot = fqn.lastIndexOf('.');
            String typePkg = dot < 0 ? "" : fqn.substring(0, dot);
            String simple = fqn.substring(dot + 1);
            boolean visible = typePkg.equals(pkg) || Pattern.compile(
                    "\\bimport\\s+" + Pattern.quote(typePkg) + "\\s*\\.\\s*\\*\\s*;").matcher(source).find();
            if (visible && Pattern.compile("(?<![\\w.$])" + Pattern.quote(simple) + "(?![\\w$])")
                    .matcher(source).find()) {
                return fqn;
            }
        }
        return null;
    }

    /**
     * Returns the fully-qualified name of the top-level type a Java file is
     * named for: its package followed by the file's base name.
     *
     * @param path   the repository-relative path of a {@code .java} file
     * @param source the file's text, or {@code null} when it is not known
     * @return the type name, or {@code null} when {@code source} is {@code null}
     */
    static String declaredType(String path, String source) {
        if (source == null) return null;
        String name = new File(path).getName();
        if (name.endsWith(".java")) name = name.substring(0, name.length() - ".java".length());
        String pkg = packageOf(source);
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /**
     * Returns the package a Java source declares.
     *
     * @param source the Java source text
     * @return the package name, or an empty string for the default package
     */
    private static String packageOf(String source) {
        Matcher m = PACKAGE.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Returns the text of every changed Java file in the job's working tree:
     * the files that may refer to a refused type.
     *
     * @return repository-relative path to file text
     */
    private Map<String, String> changedJavaSources() {
        Map<String, String> out = new LinkedHashMap<>();
        String workDir = job.getWorkingDirectory();
        if (workDir == null) return out;
        for (String file : GitOperations.getChangedFiles(workDir)) {
            File f = new File(workDir, file);
            if (!file.endsWith(".java") || GitOperations.isExcludedPath(file) || !f.isFile()) continue;
            try {
                out.put(file, Files.readString(f.toPath(), StandardCharsets.UTF_8));
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
     * @param files       the main-source files the search examined, or was to examine
     * @param failure     why the search could not run, or {@code null} when it ran
     */
    record Scan(List<NewTypeAssessment> assessments, List<String> added,
                List<String> files, String failure) {

        /** Returns the scan of a tree with nothing to examine, or with no scanner. */
        static Scan none() {
            return new Scan(List.of(), List.of(), List.of(), null);
        }

        /**
         * Returns the scan that could not run over {@code files}.
         *
         * @param files   the files the search was to examine
         * @param failure why it could not run
         * @return the failed scan
         */
        static Scan failed(List<String> files, String failure) {
            return new Scan(List.of(), List.of(), List.copyOf(files),
                    failure == null ? "unknown failure" : failure);
        }

        /**
         * Parses the JSON the scanner prints:
         * {@code {"types": [...], "added": [...]}}.
         *
         * @param json  the scanner's standard output
         * @param files the files the search examined
         * @return the scan
         * @throws IOException when the text is not the scanner's JSON
         */
        static Scan parse(String json, List<String> files) throws IOException {
            JsonNode root = MAPPER.readTree(json);
            List<String> added = new ArrayList<>();
            if (root != null) {
                for (JsonNode fqn : root.path("added")) added.add(fqn.asText());
            }
            return new Scan(NewTypeAssessment.parseAll(root), List.copyOf(added),
                    List.copyOf(files), null);
        }

        /** Returns whether the search could not run. */
        boolean failed() {
            return failure != null;
        }
    }
}
