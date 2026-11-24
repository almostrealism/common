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
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * gives the classes that show those shapes a session whose only question is their design. Its
 * mandate is to fix what it finds in the code the branch wrote, and to file a follow-up task for a
 * violation whose fix would reach into surrounding code, so the review stays proportionate to the
 * change it reviews.</p>
 *
 * <h3>What gets reviewed</h3>
 * <p>On its first evaluation in a job, the rule takes the branch's changed production classes (as
 * {@link CodingAgentJob#extractChangedFilePaths()} reports them) and keeps only those that are both
 * <em>unsettled</em> and <em>flagged</em>. That selection is a snapshot, frozen for the rest of the
 * job: a class the review session creates or edits is never gated again in the same job, so the
 * review cannot keep re-reviewing its own output.</p>
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
 * writes each settled verdict into the ledger — keyed by both the reviewed content and the content
 * the session left behind — so later jobs skip the unchanged class. With the selection frozen, one
 * session covers the job in the common case; a class that received no verdict is offered again, but
 * the set never grows. The commit
 * message describes the job's work, not the review: the primary session's {@code commit.txt} is
 * captured before the review session and restored after it, whatever the review wrote.</p>
 *
 * <h3>Visibility</h3>
 * <p>Each session announces the classes it covers as a job status notice, and after each session
 * the rule reports the cumulative wall-clock time and USD cost of the review so far (marked as a
 * lower bound when a session's cost was lost). When a session's added lines exceed
 * {@link #MAX_GROWTH_RATIO} times the added lines of the classes it reviewed, the rule posts a
 * warning: a signal that the review outgrew the change, not a hard stop.</p>
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

    /** Maximum number of classes reviewed per job, bounding the cost of the review session. */
    static final int MAX_CLASSES = 12;

    /**
     * How many times the reviewed classes' own added lines a review session may add before the rule
     * warns that the review has outgrown the change it was reviewing.
     */
    static final int MAX_GROWTH_RATIO = 3;

    /** The deterministic pre-filter that decides which changed classes are worth a session. */
    private final ObjectOrientedRiskGate gate = new ObjectOrientedRiskGate();

    /** Classes given a review session this job, by repository-relative path. */
    private final Set<String> reviewed = new LinkedHashSet<>();

    /** The classes named in the review prompt most recently built, whose session has not yet completed. */
    private List<Candidate> pending;

    /** The primary session's commit message, captured before a review session so the review cannot replace it. */
    private String savedCommitMessage;

    /** The selection computed on the rule's first evaluation this job; never recomputed. */
    private List<Candidate> selection;

    /** When the current review session's prompt was built, in epoch milliseconds. */
    private long sessionStartMillis;

    /** The job-wide cost when the current review session's prompt was built. */
    private double sessionStartCost;

    /** The working tree's changed-line count when the current review session's prompt was built. */
    private int sessionStartLines;

    /** Wall-clock time spent in review sessions this job, in milliseconds. */
    private long reviewMillis;

    /** USD cost of review sessions this job. */
    private double reviewCost;

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
     * Captures the primary commit message, clears any stale verdict scratch left on the working
     * tree before this session writes its own, announces the classes the session covers, records the
     * session's starting time, cost and changed-line count, and returns a review prompt covering
     * every flagged class not yet reviewed, up to {@link #MAX_CLASSES}.
     *
     * <p>Clearing the verdict files here, before dispatch, is what guarantees the session is settled
     * only by the lines it writes: {@link #VERDICT_PATH} is git-ignored scratch that survives
     * {@code git clean -fd}, so a verdict left by an earlier job whose session was killed before it
     * reached {@link #onCorrectionAttempted(CodingAgentJob)} would otherwise still be on the tree and
     * read back as if this session had written it.</p>
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
        discardVerdictFiles(job, verdictFiles(pending));
        StringJoiner classes = new StringJoiner(", ");
        for (Candidate candidate : pending) {
            job.log("Object-oriented design review selected " + candidate.path
                    + "; signals=" + candidate.signalNames());
            classes.add(simpleName(candidate.path));
        }
        job.harnessStatus().notice("Object-oriented design review session covering "
                + pending.size() + " class(es): " + classes);
        sessionStartMillis = System.currentTimeMillis();
        sessionStartCost = job.getTotalCost();
        sessionStartLines = changedLineCount(job);
        String base = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
        return buildReviewPrompt(pending, base);
    }

    /**
     * After a review session, restores the primary commit message the review may have overwritten,
     * records each pending class that received a verdict (as reviewed, and in the ledger keyed by
     * both the content it was reviewed at and the content the session left), and reports the
     * review's cumulative time and cost and whether the session outgrew the change. The selection
     * is deliberately kept: it is the snapshot taken on first evaluation, so nothing the session
     * created or edited becomes a new candidate.
     *
     * <p>The verdict files, cleared before the session in {@link #buildCorrectionPrompt(CodingAgentJob)},
     * carry only this session's lines; they are deleted again once read so the git-ignored scratch does
     * not outlive the session on the working tree. A class this session edited but wrote no verdict for
     * is therefore left unsettled rather than settled by a verdict some earlier session wrote.</p>
     *
     * @param job the job after the review session
     */
    @Override
    public void onCorrectionAttempted(CodingAgentJob job) {
        CommitMessageBuilder.restoreCommitTxt(job, savedCommitMessage);
        savedCommitMessage = null;
        if (pending == null) return;

        Set<String> files = verdictFiles(pending);
        Set<String> verdicts = verdictLines(job, files);
        for (Candidate candidate : pending) {
            String settled = settledVerdict(verdicts, candidate.path);
            if (settled == null) continue;
            reviewed.add(candidate.path);
            ledger(job).record(candidate.path, candidate.blobId, settled);
            String after = blobId(job, candidate.path);
            if (after != null && !after.equals(candidate.blobId)) {
                ledger(job).record(candidate.path, after, settled);
            }
        }
        discardVerdictFiles(job, files);
        reportSession(job, pending);
        pending = null;
    }

    /**
     * Accumulates the session that just ended into the review's running time and cost, posts the
     * cumulative figure as a job status notice, and warns when the lines the session added exceed
     * {@link #MAX_GROWTH_RATIO} times the added lines of the classes it reviewed.
     *
     * @param job             the job after the review session
     * @param reviewedClasses the classes the session covered
     */
    private void reportSession(CodingAgentJob job, List<Candidate> reviewedClasses) {
        reviewMillis += Math.max(0L, System.currentTimeMillis() - sessionStartMillis);
        reviewCost += Math.max(0.0, job.getTotalCost() - sessionStartCost);
        String bound = job.isCostIncomplete() ? " (lower bound: a session's cost was lost)" : "";
        job.harnessStatus().notice("Object-oriented design review so far: " + reviewed.size()
                + " class(es) reviewed, " + HarnessStatusReporter.formatDuration(reviewMillis)
                + String.format(Locale.ROOT, ", $%.2f", reviewCost) + bound);

        int primaryLines = reviewedClasses.stream().mapToInt(Candidate::addedLineCount).sum();
        int sessionLines = Math.max(0, changedLineCount(job) - sessionStartLines);
        if (sessionLines > MAX_GROWTH_RATIO * Math.max(1, primaryLines)) {
            String warning = "Object-oriented design review added " + sessionLines
                    + " lines while reviewing " + reviewedClasses.size() + " class(es) with "
                    + primaryLines + " added lines; the review outgrew the change it reviewed";
            job.warn(warning);
            job.harnessStatus().unusual(warning);
        }
    }

    /**
     * Returns an approximate count of the lines the working tree adds relative to the base branch:
     * the added lines {@code git diff --numstat} reports for tracked files plus the lines of every
     * untracked, non-ignored file. A rewritten line counts once and deletions are not counted, which
     * is enough for the proportionality warning it feeds. Package-private and non-{@code static} so
     * a test can supply canned counts without a git working tree.
     *
     * @param job the job whose working tree is measured
     * @return the added-line count, or {@code 0} when git cannot be read
     */
    int changedLineCount(CodingAgentJob job) {
        String base = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
        int total = 0;
        try {
            for (String line : job.executeGitWithOutput("diff", "--numstat", "origin/" + base).split("\n")) {
                String[] columns = line.trim().split("\t");
                if (columns.length >= 3 && columns[0].matches("\\d+")) {
                    total += Integer.parseInt(columns[0]);
                }
            }
            for (String path : job.executeGitWithOutput("ls-files", "--others", "--exclude-standard").split("\n")) {
                String trimmed = path.trim();
                if (trimmed.isEmpty() || GitOperations.isExcludedPath(trimmed)) continue;
                String content = readContent(job, trimmed);
                if (content != null) total += (int) content.lines().count();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            job.warn("Object-oriented design review: failed to count changed lines: " + e.getMessage());
        }
        return total;
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
     * Returns the changed production classes that are both unsettled in the ledger and flagged by the
     * risk gate, computed on the rule's first evaluation and frozen for the rest of the job. Freezing
     * is what keeps a review session's own edits from being gated and reviewed in turn.
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
        return new Candidate(path, blobId, signals, addedLines.size());
    }

    /**
     * Returns the git blob id of a class's current content: on selection, the content to be
     * reviewed; after a session, the content the session left. Package-private and
     * non-{@code static} so a test can supply canned ids without a git working tree.
     *
     * @param job  the job whose working tree holds the class
     * @param path the repository-relative path of the class
     * @return the git blob id of the class's current content, or {@code null} on failure
     */
    String blobId(CodingAgentJob job, String path) {
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
        ledger = ObjectOrientedReviewLedger.forBranch(job, ".tsv", ObjectOrientedReviewLedger.REVIEW_VERDICTS);
        return ledger;
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
     * Returns the repository-relative verdict files a review session may have written. A session is
     * told to write the repository-root {@link #VERDICT_PATH}, but an agent that worked from a module
     * directory writes {@code <module>/.flowtree/oop-review.md} instead; both are checked so a verdict
     * is never missed because of where it landed.
     *
     * @param candidates the classes under review, whose module roots give the files to check
     * @return the repository-relative verdict files to read and, once read, discard
     */
    private Set<String> verdictFiles(List<Candidate> candidates) {
        Set<String> files = new LinkedHashSet<>();
        files.add(VERDICT_PATH);
        for (Candidate candidate : candidates) {
            String module = moduleRoot(candidate.path);
            if (!module.isEmpty()) files.add(module + "/" + VERDICT_PATH);
        }
        return files;
    }

    /**
     * Reads every verdict line in the given files, trimmed and unioned across them.
     *
     * @param job   the job whose working tree holds the verdict files
     * @param files the repository-relative verdict files to read
     * @return the trimmed verdict lines found, unioned across every verdict file
     */
    private Set<String> verdictLines(CodingAgentJob job, Set<String> files) {
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
     * Deletes the given verdict files after a session's lines have been read, so a verdict never
     * outlives the session that wrote it. The files are git-ignored scratch; a leftover line would
     * otherwise be read by a later session or job and settle a class it never reviewed.
     *
     * @param job   the job whose working tree holds the verdict files
     * @param files the repository-relative verdict files to delete
     */
    private void discardVerdictFiles(CodingAgentJob job, Set<String> files) {
        for (String relative : files) {
            try {
                Files.deleteIfExists(Path.of(job.getWorkingDirectory(), relative));
            } catch (IOException e) {
                job.warn("Object-oriented design review: failed to clear " + relative + ": " + e.getMessage());
            }
        }
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
        sb.append("STEP 3 - Fix what this branch wrote; file a follow-up for the rest.\n");
        sb.append("a. A violation in code this branch added or changed (what `git diff origin/").append(base);
        sb.append("` shows for the listed classes): fix it now. Do not defer it to a memory or a TODO. ");
        sb.append("Update the callers, tests and docs the fix touches. Existing tests may be updated for the ");
        sb.append("new API but never weakened.\n");
        sb.append("b. A violation in surrounding code this branch did not write (a superclass, its other ");
        sb.append("subclasses, callers, sibling classes), or one whose fix would require changing such code: ");
        sb.append("do NOT edit that code in this job. File a follow-up task with tracker_create_task (the ");
        sb.append("project of this job's task, a title, and a description naming the class and the ");
        sb.append("violation) and mention its id in the verdict line.\n");
        sb.append("c. Keep the review proportionate to the change it reviews. If a fix would grow the diff ");
        sb.append("well beyond what this branch already changed in the listed classes, stop and file a ");
        sb.append("follow-up task instead. Do not create new classes or rewrite other classes to fix a ");
        sb.append("listed one.\n");
        sb.append("Compile the affected modules with `mvn install -DskipTests -pl <module> -am` and run the ");
        sb.append("tests that cover the class.\n\n");
        sb.append("STEP 4 - Record. Append one line PER CLASS to the repository-root ").append(VERDICT_PATH);
        sb.append(" (create it if needed; write it at the working-directory root, NOT inside any module ");
        sb.append("directory): `<class>: CLEAN` if nothing needed fixing, or `<class>: FIXED - <what you ");
        sb.append("changed>`. The verdict word is always CLEAN or FIXED; when you filed a follow-up under ");
        sb.append("STEP 3b, add it to that same line after the class's own verdict, e.g. `<class>: CLEAN - ");
        sb.append("follow-up <task id> filed for <surrounding class>`. Then store a memory describing any ");
        sb.append("violation you fixed.\n\n");
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

        /** How many lines the branch added to the class when it was selected. */
        private final int addedLineCount;

        /**
         * @param path           the repository-relative path
         * @param blobId         the git blob id of the current content
         * @param signals        the signals that flagged the class
         * @param addedLineCount how many lines the branch added to the class
         */
        Candidate(String path, String blobId, EnumSet<ObjectOrientedRiskGate.Signal> signals,
                  int addedLineCount) {
            this.path = path;
            this.blobId = blobId;
            this.signals = signals;
            this.addedLineCount = addedLineCount;
        }

        /**
         * Returns how many lines the branch added to the class when it was selected, the measure of
         * the change a review session's own additions are compared against.
         *
         * @return the added-line count
         */
        int addedLineCount() { return addedLineCount; }

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
