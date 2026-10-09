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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforcement rule that runs a dedicated object-oriented design review session for every
 * production Java class the branch added or modified relative to its base branch, one class
 * per session. The set is branch-wide, as {@link CodingAgentJob#extractChangedFilePaths()}
 * reports it, so a later job on the same branch reviews again a class that an earlier job
 * changed: its own edits may have broken the design of a class it did not touch.
 *
 * <p>Agents reliably write procedural code in an object-oriented codebase: classes that are not
 * the thing their name says they are, methods that accept a collaborator the receiver already
 * owns, objects built only to read one of their fields, static helpers beside the type whose
 * state they read. A general review pass sees the whole diff at once and, by design
 * ({@link ReviewPromptBuilder}), defers design problems rather than fixing them, so these
 * survive to the pull request. This rule instead gives each changed class a session of its own,
 * whose only question is the design of that class, and whose mandate is to fix what it finds.</p>
 *
 * <p>Each correction session reviews the next unreviewed class: new classes first, then
 * modified ones, in the order {@link CodingAgentJob#extractChangedFilePaths()} reports them.
 * A class a review session itself adds or modifies is reviewed in turn. The session records
 * its verdict in {@link #VERDICT_PATH}, a {@linkplain #getProgressPaths() progress path}, so that
 * a review that finds the class sound is not mistaken by {@link EnforcementRunner} for a session
 * that made no progress. A class counts as reviewed only once its verdict is in that file. At
 * most {@link #MAX_CLASSES} classes are reviewed per job.</p>
 *
 * <p>Active whenever review is enabled ({@link CodingAgentJob#isReviewEnabled()}); a job that
 * changes no production Java class has nothing for it to review. It runs after the
 * content-protection rules and before the staging and commit-message checks, so that its
 * sessions cannot exhaust the job's total enforcement budget before those rules run.</p>
 *
 * @see ReviewRule
 * @see EnforcementRunner
 */
class ObjectOrientedDesignRule implements EnforcementRule {

    /** Repository-relative file each review session appends its verdict to; scratch space, never committed. */
    static final String VERDICT_PATH = ".flowtree/oop-review.md";

    /** Maximum number of classes reviewed per job, bounding the cost of one session per class. */
    static final int MAX_CLASSES = 12;

    /** Classes already given a review session, by repository-relative path. */
    private final Set<String> reviewed = new LinkedHashSet<>();

    /** The class whose review prompt was built most recently and whose session has not yet completed. */
    private String pending;

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
     * Returns {@code true} while a changed production class remains unreviewed and fewer than
     * {@link #MAX_CLASSES} classes have been reviewed.
     *
     * @param job the job whose working tree is inspected
     * @return whether another review session is due
     */
    @Override
    public boolean isViolated(CodingAgentJob job) {
        if (job.getWorkingDirectory() == null) return false;
        return reviewed.size() < MAX_CLASSES && !getUnreviewedClasses(job).isEmpty();
    }

    /**
     * Selects the next unreviewed class and returns the review prompt for it.
     *
     * @param job the job whose working tree is inspected
     * @return the review prompt for one class
     */
    @Override
    public String buildCorrectionPrompt(CodingAgentJob job) {
        pending = getUnreviewedClasses(job).get(0);
        String base = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
        return buildReviewPrompt(pending, getChangedClasses(job), base);
    }

    /**
     * Records the class whose review session just completed as reviewed, provided the session
     * appended its verdict for that class to {@link #VERDICT_PATH}. A session that failed or
     * wrote no verdict leaves the class unreviewed, so the next session reviews it again; a
     * class that never receives a verdict stops the rule making progress, which
     * {@link EnforcementRunner} reports, rather than being skipped silently.
     *
     * @param job the job after the review session
     */
    @Override
    public void onCorrectionAttempted(CodingAgentJob job) {
        if (pending != null && hasVerdict(job, pending)) reviewed.add(pending);
        pending = null;
    }

    /**
     * Returns whether {@link #VERDICT_PATH} holds a verdict line for a class: the class path
     * followed by {@code : CLEAN} or {@code : FIXED}, as the review prompt requires.
     *
     * @param job    the job whose working tree holds the verdict file
     * @param target the repository-relative path of the class
     * @return whether a verdict for {@code target} was recorded
     */
    private boolean hasVerdict(CodingAgentJob job, String target) {
        Path verdicts = Path.of(job.getWorkingDirectory(), VERDICT_PATH);
        if (!Files.isRegularFile(verdicts)) return false;

        try {
            return Files.readAllLines(verdicts, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .anyMatch(line -> line.startsWith(target + ": CLEAN")
                            || line.startsWith(target + ": FIXED"));
        } catch (IOException e) {
            job.warn("Object-oriented design review: failed to read " + VERDICT_PATH + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns the classes already given a review session.
     *
     * @return repository-relative paths, in review order
     */
    Set<String> getReviewed() { return Collections.unmodifiableSet(reviewed); }

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
     * Returns the changed production classes not yet reviewed.
     *
     * @param job the job whose working tree is inspected
     * @return repository-relative paths, in review order
     */
    private List<String> getUnreviewedClasses(CodingAgentJob job) {
        return getChangedClasses(job).stream()
                .filter(path -> !reviewed.contains(path))
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
     * Builds the prompt of the review session for one class.
     *
     * @param target  the class under review
     * @param changed every changed production class of the job, for context
     * @param base    the base branch the job's changes are measured against
     * @return the prompt
     */
    String buildReviewPrompt(String target, List<String> changed, String base) {
        StringBuilder sb = new StringBuilder();
        sb.append("OBJECT-ORIENTED DESIGN REVIEW: ").append(target).append("\n\n");
        sb.append("This session reviews ONE class: ").append(target).append("\n");
        sb.append("It exists because agents in this project keep writing procedural code in an ");
        sb.append("object-oriented codebase, and the owner has rejected that work in review again ");
        sb.append("and again. Your first instinct for a design is reliably procedural; treat the ");
        sb.append("existing shape of this class as suspect, not as a given.\n\n");
        sb.append("Other production classes this branch changed (context only; each gets its own session):\n");
        for (String path : changed) {
            if (!path.equals(target)) sb.append("  ").append(path).append("\n");
        }
        sb.append("\nSTEP 1 - Read. Read the whole class, every supertype and interface it extends or ");
        sb.append("implements, and every caller of its public and protected members (Grep for them). ");
        sb.append("Run `git diff origin/").append(base).append(" -- ").append(target).append("` to see what this branch did to it.\n\n");
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
        sb.append("STEP 3 - Fix. Fix every violation in this class NOW, even when the fix is a refactor that ");
        sb.append("touches other files: change the type hierarchy, move methods to the types they belong ");
        sb.append("on, update every caller, test and doc. Do not defer to a memory, a TODO or a follow-up; ");
        sb.append("deferral is how these violations reached review. Existing tests may be updated for the ");
        sb.append("new API but never weakened. Compile the affected modules with ");
        sb.append("`mvn install -DskipTests -pl <module> -am` and run the tests that cover the class.\n\n");
        sb.append("STEP 4 - Record. Append one line to ").append(VERDICT_PATH).append(" (create it if needed): ");
        sb.append("`").append(target).append(": CLEAN` if nothing needed fixing, or `").append(target);
        sb.append(": FIXED - <what you changed>`. Then store a memory describing any violation you fixed.\n");
        return sb.toString();
    }
}
