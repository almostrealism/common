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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Enforcement rule that runs a single object-oriented design review session over the production
 * Java classes a branch changed and a cheap, deterministic {@link ObjectOrientedRiskGate} judges
 * worth reviewing.
 *
 * <p>Agents reliably write procedural code in an object-oriented codebase: classes that are not the
 * thing their name says they are, methods that accept a collaborator the receiver already owns,
 * objects built only to read one of their fields, static helpers beside the type whose state they
 * read. A general review pass sees the whole diff at once and, by design ({@link ReviewPromptBuilder}),
 * defers design problems rather than fixing them, so these survive to the pull request. This rule
 * gives the classes that show those shapes a session whose only question is their design, and whose
 * mandate is to fix what it finds.</p>
 *
 * <h3>What gets reviewed</h3>
 * <p>Every job, the rule takes the branch's changed production classes (as
 * {@link CodingAgentJob#extractChangedFilePaths()} reports them) and keeps only those that are both
 * <em>unsettled</em> and <em>flagged</em>:</p>
 * <ul>
 *   <li><b>Unsettled</b> — the class's current content, identified by its git blob id, does not
 *       already carry a {@code CLEAN} or {@code FIXED} verdict in the branch's
 *       {@link ObjectOrientedReviewLedger}. Because the blob id changes with any edit, a class is
 *       reviewed again exactly when its content changed since it was last judged, and a class an
 *       earlier job already cleared is skipped — the repetition that made the old per-class review
 *       expensive.</li>
 *   <li><b>Flagged</b> — {@link ObjectOrientedRiskGate} saw at least one design signal in the
 *       class's name or the lines the branch added. A class the gate does not flag is left alone,
 *       so a session is spent only where a reviewer plausibly has something to fix.</li>
 * </ul>
 *
 * <p>All flagged classes are reviewed together in one session, up to {@link #MAX_CLASSES}, rather
 * than one session per class. The session records a verdict line per class in {@link #VERDICT_PATH}
 * (a {@linkplain #getProgressPaths() progress path}, scratch space never committed), and the rule
 * writes each settled verdict into the ledger so later jobs skip the unchanged class. The commit
 * message describes the job's work, not the review: the primary session's {@code commit.txt} is
 * captured before the review session and restored after it, whatever the review wrote.</p>
 *
 * <p>Active by default when review is enabled ({@link CodingAgentJob#isReviewEnabled()}); a job may
 * switch it off ({@link CodingAgentJob#isObjectOrientedReviewEnabled()}). It runs after the
 * content-protection rules and before the staging and commit-message checks, so its session cannot
 * exhaust the job's enforcement budget before those rules run.</p>
 *
 * @see ObjectOrientedRiskGate
 * @see ObjectOrientedReviewLedger
 * @see ReviewRule
 * @see EnforcementRunner
 */
class ObjectOrientedDesignRule implements EnforcementRule {

    /** Repository-relative file each review session appends its verdict to; scratch space, never committed. */
    static final String VERDICT_PATH = ".flowtree/oop-review.md";

    /** Directory, under the workspace parent beside the workspace lock, that holds the per-branch ledgers. */
    static final String LEDGER_DIR = ".flowtree-locks/oop-verdicts";

    /** Maximum number of classes reviewed per job, bounding the cost of the review session. */
    static final int MAX_CLASSES = 12;

    /** The deterministic pre-filter that decides which changed classes are worth a session. */
    private final ObjectOrientedRiskGate gate = new ObjectOrientedRiskGate();

    /** Classes given a review session this job, by repository-relative path. */
    private final Set<String> reviewed = new LinkedHashSet<>();

    /** The classes named in the review prompt most recently built, whose session has not yet completed. */
    private List<Candidate> pending;

    /** The primary session's commit message, captured before a review session so the review cannot replace it. */
    private String savedCommitMessage;

    /** Memoized selection for the current working-tree state; cleared after each session. */
    private List<Candidate> selection;

    /** The branch's verdict ledger, resolved once per job. */
    private ObjectOrientedReviewLedger ledger;

    @Override
    public String getName() { return "object-oriented-design"; }

    /**
     * Allows as many sessions per enforcement pass as the runner's per-pass ceiling, so that the
     * rule continues in a later pass, rather than being retired, when more classes remain.
     *
     * @return {@link CodingAgentJob#DEFAULT_MAX_RULE_ENTRIES}
     */
    @Override
    public int getMaxRetries() { return CodingAgentJob.DEFAULT_MAX_RULE_ENTRIES; }

    @Override
    public Set<String> getProgressPaths() { return Set.of(VERDICT_PATH); }

    /**
     * Returns {@code true} while a flagged, unsettled production class remains unreviewed this job
     * and fewer than {@link #MAX_CLASSES} classes have been reviewed.
     *
     * @param job the job whose working tree is inspected
     * @return whether another review session is due
     */
    @Override
    public boolean isViolated(CodingAgentJob job) {
        if (job.getWorkingDirectory() == null) return false;
        return reviewed.size() < MAX_CLASSES && !unreviewed(job).isEmpty();
    }

    /**
     * Captures the primary commit message and returns a review prompt covering every flagged class
     * not yet reviewed, up to {@link #MAX_CLASSES}.
     *
     * @param job the job whose working tree is inspected
     * @return the review prompt for the pending classes
     */
    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        savedCommitMessage = CommitMessageBuilder.captureCommitTxt(job);
        List<Candidate> remaining = unreviewed(job);
        int room = Math.max(0, MAX_CLASSES - reviewed.size());
        pending = remaining.subList(0, Math.min(room, remaining.size()));
        for (Candidate candidate : pending) {
            job.log("Object-oriented design review selected " + candidate.path
                    + "; signals=" + candidate.signalNames());
        }
        String base = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
        return buildReviewPrompt(pending, base);
    }

    /**
     * After a review session, restores the primary commit message the review may have overwritten,
     * records each pending class that received a verdict (as reviewed, and in the ledger keyed by
     * its current content), and clears the memoized selection so the next check re-scans.
     *
     * @param job the job after the review session
     */
    @Override
    public void onCorrectionAttempted(CodingAgentJob job) {
        CommitMessageBuilder.restoreCommitTxt(job, savedCommitMessage);
        savedCommitMessage = null;

        if (pending != null) {
            Set<String> verdicts = verdictLines(job, pending);
            for (Candidate candidate : pending) {
                String settled = settledVerdict(verdicts, candidate.path);
                if (settled != null) {
                    reviewed.add(candidate.path);
                    ledger(job).record(candidate.path, candidate.blobId, settled);
                }
            }
        }
        pending = null;
        selection = null;
    }

    /**
     * Returns the flagged, unsettled classes not yet reviewed this job.
     *
     * @param job the job whose working tree is inspected
     * @return candidates in review order
     */
    private List<Candidate> unreviewed(CodingAgentJob job) {
        return selection(job).stream()
                .filter(candidate -> !reviewed.contains(candidate.path))
                .collect(Collectors.toList());
    }

    /**
     * Returns, computing once per working-tree state, the changed production classes that are both
     * unsettled in the ledger and flagged by the risk gate.
     *
     * @param job the job whose working tree is inspected
     * @return the flagged, unsettled candidates in review order
     */
    private List<Candidate> selection(CodingAgentJob job) {
        if (selection != null) return selection;
        String base = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
        selection = new ArrayList<>();
        for (String path : getChangedClasses(job)) {
            Candidate candidate = inspect(job, path, base);
            if (candidate == null) continue;
            if (ledger(job).isReviewed(candidate.path, candidate.blobId)) continue;
            if (candidate.signals.isEmpty()) continue;
            selection.add(candidate);
        }
        return selection;
    }

    /**
     * Inspects one changed class: computes its git blob id, asks {@link ObjectOrientedRiskGate}
     * which design signals its name and added lines fire, and returns the result. Package-private
     * and non-{@code static} so a test can supply canned inspections without a git working tree.
     *
     * @param job  the job whose working tree holds the class
     * @param path the repository-relative path of the class
     * @param base the base branch the job's changes are measured against
     * @return the candidate, or {@code null} when the blob id cannot be computed
     */
    Candidate inspect(CodingAgentJob job, String path, String base) {
        String blobId = blobId(job, path);
        if (blobId == null) return null;
        boolean newClass = !existsAtBase(job, path, base);
        List<String> addedLines = addedLines(job, path, base);
        String content = readContent(job, path);
        EnumSet<ObjectOrientedRiskGate.Signal> signals =
                gate.evaluate(simpleName(path), newClass, addedLines, content);
        return new Candidate(path, blobId, signals);
    }

    /**
     * @param job  the job whose working tree holds the class
     * @param path the repository-relative path of the class
     * @return the git blob id of the class's current content, or {@code null} on failure
     */
    private String blobId(CodingAgentJob job, String path) {
        try {
            String out = job.executeGitWithOutput("hash-object", path).trim();
            return out.isEmpty() ? null : out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            job.warn("Object-oriented design review: failed to hash " + path + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * @param job  the job whose working tree holds the class
     * @param path the repository-relative path of the class
     * @param base the base branch
     * @return whether the class exists on {@code origin/base} (so is not new on this branch)
     */
    private boolean existsAtBase(CodingAgentJob job, String path, String base) {
        try {
            return job.executeGit("cat-file", "-e", "origin/" + base + ":" + path) == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * @param job  the job whose working tree holds the class
     * @param path the repository-relative path of the class
     * @param base the base branch
     * @return the lines the branch added to the class, with the diff {@code +} stripped
     */
    private List<String> addedLines(CodingAgentJob job, String path, String base) {
        List<String> added = new ArrayList<>();
        try {
            for (String line : job.executeGitWithOutput("diff", "origin/" + base, "--", path).split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) added.add(line.substring(1));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            job.warn("Object-oriented design review: failed to diff " + path + ": " + e.getMessage());
        }
        return added;
    }

    /**
     * @param job  the job whose working tree holds the class
     * @param path the repository-relative path of the class
     * @return the class's current source, or {@code null} when it cannot be read
     */
    private String readContent(CodingAgentJob job, String path) {
        try {
            return Files.readString(Path.of(job.getWorkingDirectory(), path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the branch's verdict ledger, resolving it once per job. A job without a working
     * directory or target branch gets a ledger over no file, which never skips or records.
     * Package-private and non-{@code static} so a test can redirect it to a temporary file.
     *
     * @param job the job whose branch the ledger is for
     * @return the ledger
     */
    ObjectOrientedReviewLedger ledger(CodingAgentJob job) {
        if (ledger != null) return ledger;
        Path file = ledgerFile(job);
        ledger = new ObjectOrientedReviewLedger(file);
        return ledger;
    }

    /**
     * @param job the job whose branch the ledger is for
     * @return the ledger file under the workspace parent, or {@code null} when it cannot be resolved
     */
    private Path ledgerFile(CodingAgentJob job) {
        String workingDirectory = job.getWorkingDirectory();
        String branch = job.getTargetBranch() != null ? job.getTargetBranch() : job.getBaseBranch();
        if (workingDirectory == null || branch == null || branch.isEmpty()) return null;
        Path repoRoot = Path.of(workingDirectory);
        Path parent = repoRoot.getParent();
        if (parent == null) return null;
        String repoName = repoRoot.getFileName() != null ? repoRoot.getFileName().toString() : "repo";
        return parent.resolve(LEDGER_DIR).resolve(repoName).resolve(sanitize(branch) + ".tsv");
    }

    /**
     * @param branch a branch name, which may contain path separators
     * @return the name with characters unsafe for a filename replaced by {@code -}
     */
    private static String sanitize(String branch) {
        return branch.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /**
     * Returns the existing production Java classes the branch added or modified.
     *
     * @param job the job whose working tree is inspected
     * @return repository-relative paths, new classes first
     */
    private List<String> getChangedClasses(CodingAgentJob job) {
        File root = new File(job.getWorkingDirectory());
        return job.extractChangedFilePaths().stream()
                .filter(this::isProductionClass)
                .filter(path -> new File(root, path).isFile())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Returns whether a path is a production Java type: a {@code .java} file under a
     * {@code src/main/java} source root, other than package and module descriptors.
     *
     * @param path a repository-relative path
     * @return whether the path holds a production class
     */
    private boolean isProductionClass(String path) {
        return path.endsWith(".java")
                && (path.startsWith("src/main/java/") || path.contains("/src/main/java/"))
                && !path.endsWith("package-info.java")
                && !path.endsWith("module-info.java");
    }

    /**
     * @param path a repository-relative class path
     * @return the class's simple name, without package or {@code .java} extension
     */
    private static String simpleName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : name;
    }

    /**
     * Reads every verdict line a review session may have written. A session is told to write the
     * repository-root {@link #VERDICT_PATH}, but an agent that worked from a module directory writes
     * {@code <module>/.flowtree/oop-review.md} instead; both are read so a verdict is never missed
     * because of where it landed.
     *
     * @param job        the job whose working tree holds the verdict files
     * @param candidates the classes under review, whose module roots give the files to check
     * @return the trimmed verdict lines found, unioned across every verdict file
     */
    private Set<String> verdictLines(CodingAgentJob job, List<Candidate> candidates) {
        Set<String> files = new LinkedHashSet<>();
        files.add(VERDICT_PATH);
        for (Candidate candidate : candidates) {
            String module = moduleRoot(candidate.path);
            if (!module.isEmpty()) files.add(module + "/" + VERDICT_PATH);
        }
        Set<String> lines = new LinkedHashSet<>();
        for (String relative : files) {
            Path verdicts = Path.of(job.getWorkingDirectory(), relative);
            if (!Files.isRegularFile(verdicts)) continue;
            try {
                Files.readAllLines(verdicts, StandardCharsets.UTF_8).stream()
                        .map(String::trim).forEach(lines::add);
            } catch (IOException e) {
                job.warn("Object-oriented design review: failed to read " + relative + ": " + e.getMessage());
            }
        }
        return lines;
    }

    /**
     * @param path a repository-relative class path
     * @return the module root (the part before {@code /src/main/java/}), or empty for a root module
     */
    private static String moduleRoot(String path) {
        int src = path.indexOf("/src/main/java/");
        return src > 0 ? path.substring(0, src) : "";
    }

    /**
     * Returns the settled verdict recorded for a class — the word after {@code <path>: } when it is
     * {@code CLEAN} or {@code FIXED} — or {@code null} when none was recorded.
     *
     * @param verdictLines the trimmed verdict lines read from the working tree
     * @param target       the repository-relative path of the class
     * @return {@code "CLEAN"}, {@code "FIXED"}, or {@code null}
     */
    private static String settledVerdict(Set<String> verdictLines, String target) {
        for (String line : verdictLines) {
            if (line.startsWith(target + ": CLEAN")) return "CLEAN";
            if (line.startsWith(target + ": FIXED")) return "FIXED";
        }
        return null;
    }

    /**
     * Returns the classes already given a review session this job.
     *
     * @return repository-relative paths, in review order
     */
    Set<String> getReviewed() { return Collections.unmodifiableSet(reviewed); }

    /**
     * Builds the prompt of the review session covering every pending class.
     *
     * @param candidates the classes to review, with the signals that flagged each
     * @param base       the base branch the job's changes are measured against
     * @return the prompt
     */
    String buildReviewPrompt(List<Candidate> candidates, String base) {
        StringBuilder sb = new StringBuilder();
        sb.append("OBJECT-ORIENTED DESIGN REVIEW\n\n");
        sb.append("This session reviews the design of the classes listed below, which this branch ");
        sb.append("changed and a risk check flagged. It exists because agents in this project keep ");
        sb.append("writing procedural code in an object-oriented codebase, and the owner has rejected ");
        sb.append("that work in review again and again. Your first instinct for a design is reliably ");
        sb.append("procedural; treat the existing shape of each class as suspect, not as a given.\n\n");
        sb.append("CLASSES TO REVIEW (each with the design signals that flagged it):\n");
        for (Candidate candidate : candidates) {
            sb.append("  ").append(candidate.path).append("  [").append(candidate.signalDescriptions()).append("]\n");
        }
        sb.append("\nReview each class in turn. For each one:\n\n");
        sb.append("STEP 1 - Read. Read the whole class, every supertype and interface it extends or ");
        sb.append("implements, and every caller of its public and protected members (Grep for them). ");
        sb.append("Run `git diff origin/").append(base).append(" -- <class>` to see what this branch did to it. ");
        sb.append("The flagged signals are only a hint about where to look; judge the whole class.\n\n");
        sb.append("STEP 2 - Check every one of these. Each is a violation the owner has rejected:\n\n");
        sb.append("1. IDENTITY. A class IS what its name says. A class named ...Model must extend the ");
        sb.append("framework's Model (or AutoregressiveModel, or whichever model type it names); ...Block ");
        sb.append("must implement Block; ...Cell must implement Cell; ...Config must be a configuration. ");
        sb.append("A class that builds and returns some other type is either that type (extend it) or a ");
        sb.append("factory and named ...Factory. A class that is neither the thing nor its factory is wrong.\n");
        sb.append("2. OWN YOUR COLLABORATORS. A method must not accept, as a parameter, an object the ");
        sb.append("receiver should derive from its own state (for example taking 'any CompiledModel' ");
        sb.append("instead of compiling the model it is). If the argument must correspond to `this`, ");
        sb.append("`this` produces it.\n");
        sb.append("3. NO REACHING INTO OBJECTS. Never read another object's field directly ");
        sb.append("(`new Window(...).generator`, `other.buffer`); never build an object only to pull one ");
        sb.append("field out of it. Ask the object, or make the object be the thing you wanted.\n");
        sb.append("4. SUBCLASS, DON'T WIRE LAMBDAS. When a class configures a framework type by passing ");
        sb.append("lambdas that close over a helper object (often an inner class holding the state those ");
        sb.append("lambdas need), the helper is a subclass of that type in disguise. Extend the type and ");
        sb.append("override its steps; add protected template-method hooks to the supertype if it has none.\n");
        sb.append("5. BEHAVIOUR LIVES ON THE TYPE WHOSE STATE IT READS. A method that reads only another ");
        sb.append("type's state belongs on that type. A static method whose signature never mentions its ");
        sb.append("own class is on the wrong class. Validation of a configuration belongs to the ");
        sb.append("configuration type, not to a static helper on its consumer. Look for an existing type ");
        sb.append("that already models the concept (a Config base class, a Features mixin) before adding one.\n");
        sb.append("6. NO UTILITY, HELPER, CONVERTER OR EXPORTER CLASSES, and no general-purpose private ");
        sb.append("helpers on a subclass that the next consumer could not find.\n");
        sb.append("7. HONOR THE INTERFACE. No instanceof or casts to a particular implementation, no member ");
        sb.append("that only works for one implementation, no members re-exposing the concrete class a ");
        sb.append("constructor merely adapts.\n");
        sb.append("8. HONOR THE SUPERCLASS CONTRACT. A subclass must keep every inherited guarantee; read the ");
        sb.append("supertype's javadoc (for example, a Model is compiled once) and design around it.\n\n");
        sb.append("STEP 3 - Fix. Fix every violation NOW, even when the fix is a refactor that touches other ");
        sb.append("files: change the type hierarchy, move methods to the types they belong on, update every ");
        sb.append("caller, test and doc. Do not defer to a memory, a TODO or a follow-up; deferral is how ");
        sb.append("these violations reached review. Existing tests may be updated for the new API but never ");
        sb.append("weakened. Compile the affected modules with `mvn install -DskipTests -pl <module> -am` and ");
        sb.append("run the tests that cover the class.\n\n");
        sb.append("STEP 4 - Record. Append one line PER CLASS to the repository-root ").append(VERDICT_PATH);
        sb.append(" (create it if needed; write it at the working-directory root, NOT inside any module ");
        sb.append("directory): `<class>: CLEAN` if nothing needed fixing, or `<class>: FIXED - <what you ");
        sb.append("changed>`. Then store a memory describing any violation you fixed.\n\n");
        sb.append("Do NOT write or edit commit.txt; the commit message describes the job's work, not this ");
        sb.append("review.\n");
        return sb.toString();
    }

    /**
     * One changed production class considered for review, with the git blob id of its current
     * content and the design signals the gate fired on it.
     */
    static final class Candidate {

        /** The repository-relative path of the class. */
        private final String path;

        /** The git blob id of the class's current content. */
        private final String blobId;

        /** The design signals the gate fired on the class. */
        private final EnumSet<ObjectOrientedRiskGate.Signal> signals;

        /**
         * @param path    the repository-relative path
         * @param blobId  the git blob id of the current content
         * @param signals the signals that flagged the class
         */
        Candidate(String path, String blobId, EnumSet<ObjectOrientedRiskGate.Signal> signals) {
            this.path = path;
            this.blobId = blobId;
            this.signals = signals;
        }

        /**
         * Returns the class's repository-relative path.
         *
         * @return the repository-relative path of the class
         */
        String path() { return path; }

        /**
         * Returns the signals that flagged the class.
         *
         * @return the signals that flagged the class
         */
        EnumSet<ObjectOrientedRiskGate.Signal> signals() { return signals; }

        /**
         * Joins the fired signals' enum names for logging.
         *
         * @return the fired signals' enum names, comma-separated, for logging
         */
        String signalNames() {
            StringJoiner joiner = new StringJoiner(", ");
            for (ObjectOrientedRiskGate.Signal signal : signals) joiner.add(signal.name());
            return joiner.toString();
        }

        /**
         * Joins the fired signals' human-readable descriptions for the prompt.
         *
         * @return the fired signals' human-readable descriptions, comma-separated, for the prompt
         */
        String signalDescriptions() {
            StringJoiner joiner = new StringJoiner("; ");
            for (ObjectOrientedRiskGate.Signal signal : signals) joiner.add(signal.description());
            return joiner.toString();
        }
    }
}
