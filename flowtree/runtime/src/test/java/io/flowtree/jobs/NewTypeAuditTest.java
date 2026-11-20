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

import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the harness half of the duplicate-type gate: {@link NewTypeAudit}
 * detecting new types that resemble existing ones (by running the repository's
 * type-equivalence search against a synthetic repository), deciding which of
 * them may not be staged, and {@link FileStager} refusing them with the reason.
 *
 * <p>The synthetic repository mirrors the incident the gate exists for: an
 * arithmetic index sequence already exists, and the branch adds a renamed
 * equivalent of it beside an unrelated, genuinely new concept.</p>
 *
 * @author Michael Murray
 */
public class NewTypeAuditTest extends TestSuiteBase {

    /** Directory of the synthetic sequence package. */
    private static final String SEQ = "mod/src/main/java/org/example/seq/";

    /** The renamed equivalent the branch adds. */
    private static final String PROGRESSION = SEQ + "IndexProgression.java";

    /** The genuinely new concept the branch adds. */
    private static final String ROUTER = "mod/src/main/java/org/example/net/PacketRouter.java";

    /** The existing type the renamed equivalent duplicates. */
    private static final String CANDIDATE = "org.example.seq.ArithmeticIndexSequence";

    /** The temporary repository. */
    private Path repo;

    /**
     * Creates a repository whose committed history holds an index sequence
     * hierarchy and unrelated filler types, with the scanner beside it.
     */
    @Before
    public void setUp() throws Exception {
        repo = Files.createTempDirectory("new-type-audit");
        git("init", "--quiet", "--initial-branch=master");
        git("config", "user.email", "audit-test@example.com");
        git("config", "user.name", "Audit Test");
        git("config", "commit.gpgsign", "false");
        write(SEQ + "IndexSequence.java", "package org.example.seq;\n\n"
                + "/** A sequence of index values, addressed by position. */\n"
                + "public interface IndexSequence {\n\tlong valueAt(int pos);\n}\n");
        write(SEQ + "ArithmeticIndexSequence.java", "package org.example.seq;\n\n"
                + "/**\n * An {@link IndexSequence} that represents arithmetic sequences of"
                + " index\n * values, offset + scale * pos.\n */\n"
                + "public class ArithmeticIndexSequence implements IndexSequence {\n"
                + "\tprivate long scale;\n\n"
                + "\tpublic long valueAt(int pos) { return scale * pos; }\n"
                + "}\n");
        String[][] fillers = {
                {"ColorPalette", "A named collection of display colors."},
                {"MeshVertex", "A point of a triangle mesh in model space."},
                {"CurrencyAmount", "A sum of money in one currency."},
                {"RetryPolicy", "When and how often a failed request is retried."},
                {"ShaderProgram", "A compiled program run on the graphics card."},
                {"UserAccount", "The login and profile of a person using the service."},
                {"ChordVoicing", "The notes of a musical chord and their octaves."},
                {"SensorReading", "One measurement reported by a hardware sensor."},
                {"LogRotation", "When old log files are archived and removed."},
                {"TaxBracket", "A range of income taxed at a single rate."}};
        for (String[] filler : fillers) {
            write("mod/src/main/java/org/example/misc/" + filler[0] + ".java",
                    "package org.example.misc;\n\n/** " + filler[1] + " */\npublic class "
                            + filler[0] + " {\n\tpublic int id() { return 0; }\n}\n");
        }
        Files.createDirectories(repo.resolve(".claude/hooks/lib"));
        Files.copy(scannerSource(), repo.resolve(NewTypeAudit.SCANNER_PATH));
        git("add", "-A");
        git("commit", "--quiet", "-m", "initial");
        git("update-ref", "refs/remotes/origin/master", "HEAD");
    }

    /** Deletes the temporary repository and the audit outcomes recorded beside it. */
    @After
    public void tearDown() throws IOException {
        if (repo == null) return;
        Path outcomes = repo.getParent().resolve(ObjectOrientedReviewLedger.LEDGER_DIR)
                .resolve(repo.getFileName().toString());
        for (Path dir : List.of(repo, outcomes)) {
            if (!Files.exists(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** A renamed equivalent is detected with its candidate; a new concept is not. */
    @Test(timeout = 300000)
    public void scanDetectsRenamedEquivalentOnly() throws Exception {
        addBranchTypes();
        CodingAgentJob job = job();
        List<NewTypeAssessment> found = job.getNewTypeAudit().scan();
        assertEquals(describeAll(found), 1, found.size());
        NewTypeAssessment progression = found.get(0);
        Assert.assertEquals("org.example.seq.IndexProgression", progression.getFqn());
        Assert.assertEquals(PROGRESSION, progression.getPath());
        assertTrue(describeAll(found), candidateNames(progression).contains(CANDIDATE));
        assertFalse(progression.buildsOnCandidate());
    }

    /** Editing a committed type adds nothing to audit. */
    @Test(timeout = 300000)
    public void editOfCommittedTypeIsNotScanned() throws Exception {
        Path existing = repo.resolve(SEQ + "ArithmeticIndexSequence.java");
        String text = Files.readString(existing, StandardCharsets.UTF_8);
        Files.writeString(existing, text.replace("\tprivate long scale;\n",
                "\tprivate long scale;\n\n\tpublic long scale() { return scale; }\n"));
        CodingAgentJob job = job();
        assertTrue(job.getNewTypeAudit().scan().isEmpty());
    }

    /**
     * A new type that extends its candidate without overriding or calling
     * anything of it does not build on it, so it is still refused.
     */
    @Test(timeout = 300000)
    public void namingCandidateAsSupertypeWithoutUsingItIsRefused() throws Exception {
        write(PROGRESSION, "package org.example.seq;\n\n"
                + "/** An arithmetic progression of index values. */\n"
                + "public final class IndexProgression extends ArithmeticIndexSequence {\n"
                + "\tpublic long first() { return 0; }\n}\n");
        CodingAgentJob job = job();
        List<NewTypeAssessment> found = job.getNewTypeAudit().scan();
        assertEquals(describeAll(found), 1, found.size());
        assertTrue(describeAll(found), found.get(0).isDuplicate());
        assertFalse(found.get(0).buildsOnCandidate());
        assertTrue(job.getNewTypeRefusals().containsKey(PROGRESSION));
    }

    /** A new type that extends its candidate and overrides it is assessed but never refused or audited. */
    @Test(timeout = 300000)
    public void typeBuildingOnCandidateIsNeitherAuditedNorRefused() throws Exception {
        write(PROGRESSION, "package org.example.seq;\n\n"
                + "/** An arithmetic progression of index values. */\n"
                + "public final class IndexProgression extends ArithmeticIndexSequence {\n"
                + "\tpublic long valueAt(int pos) { return super.valueAt(pos) + 1; }\n}\n");
        CodingAgentJob job = job();
        List<NewTypeAssessment> found = job.getNewTypeAudit().scan();
        assertEquals(describeAll(found), 1, found.size());
        assertTrue(found.get(0).buildsOnCandidate());
        assertTrue(job.getNewTypeAudit().unaudited().isEmpty());
        assertTrue(job.getNewTypeRefusals().isEmpty());
    }

    /**
     * The refusal lifts only once this job's audit examined the type AND the
     * ledger records the kept verdict; a verdict written before the audit ran,
     * or an audit without the verdict, leaves it refused.
     */
    @Test(timeout = 300000)
    public void refusalLiftsOnlyAfterAuditKeepsType() throws Exception {
        addBranchTypes();
        String fqn = "org.example.seq.IndexProgression";
        NewTypeAudit audit = job().getNewTypeAudit();
        Map<String, String> before = audit.refusals();
        assertNotNull("an unaudited duplicate is refused", before.get(PROGRESSION));
        assertTrue(before.get(PROGRESSION).contains(CANDIDATE));
        assertTrue(before.get(PROGRESSION).contains(NewTypeAudit.LEDGER_PATH));

        audit.markAudited(List.of(fqn));
        assertNotNull("an audited type without the kept verdict stays refused",
                audit.refusals().get(PROGRESSION));

        Files.writeString(repo.resolve(NewTypeAudit.LEDGER_PATH),
                "{\"types\": [{\"type\": \"" + fqn + "\","
                        + " \"audit\": {\"verdict\": \"kept\", \"reason\": \"verified\"}}]}");
        assertNotNull("a verdict written outside the audit session must not count",
                audit.refusals().get(PROGRESSION));

        audit.markAudited(List.of(fqn));
        assertNull("an audited type the audit kept may be staged", audit.refusals().get(PROGRESSION));
    }

    /** The staging guardrail skips a refused new type with the reason and stages the rest. */
    @Test(timeout = 300000)
    public void stagingRefusesUnresolvedNewType() throws Exception {
        addBranchTypes();
        CodingAgentJob job = job();
        Map<String, String> refusals = job.getNewTypeRefusals();
        Assert.assertEquals(refusals.toString(), List.of(PROGRESSION), new ArrayList<>(refusals.keySet()));

        StagingResult result = new FileStager().evaluateFiles(List.of(PROGRESSION, ROUTER),
                FileStagingConfig.forJob(job), repo.toFile(), job.asGitOperations());
        Assert.assertEquals(List.of(ROUTER), result.getStagedFiles());
        assertEquals(1, result.getSkippedFiles().size());
        String skipped = result.getSkippedFiles().get(0);
        assertTrue(skipped, skipped.startsWith(PROGRESSION + " ("));
        assertTrue(skipped, skipped.contains("has no resolved verdict"));
        assertTrue(skipped, skipped.contains(CANDIDATE));
    }

    /** The type audit marks what it was given once its session ends, lifting a kept refusal. */
    @Test(timeout = 300000)
    public void auditSessionSettlesKeptType() throws Exception {
        addBranchTypes();
        Files.writeString(repo.resolve(NewTypeAudit.LEDGER_PATH),
                "{\"types\": [{\"type\": \"org.example.seq.IndexProgression\","
                        + " \"audit\": {\"verdict\": \"kept\", \"reason\": \"verified\"}}]}");
        CodingAgentJob job = job();
        DeduplicationRule rule = new DeduplicationRule(2, false);
        assertTrue(rule.isViolated(job));
        String prompt = rule.buildCorrectionPrompt(job);
        assertTrue(prompt.contains("org.example.seq.IndexProgression"));
        assertFalse(job.getNewTypeRefusals().isEmpty());

        rule.onCorrectionAttempted(job);
        NewTypeAssessment progression = job.getNewTypeAudit().scan().get(0);
        Assert.assertEquals(NewTypeAudit.KEPT, job.getNewTypeAudit().outcome(progression));
        assertTrue(job.getNewTypeRefusals().isEmpty());
        assertFalse("nothing is left for the type audit", rule.isViolated(job));

        assertTrue("a later job on the branch reuses the recorded outcome",
                job().getNewTypeRefusals().isEmpty());

        Files.writeString(repo.resolve(NewTypeAudit.LEDGER_PATH),
                "{\"types\": [{\"type\": \"org.example.seq.IndexProgression\","
                        + " \"audit\": {\"verdict\": \"kept\", \"reason\": \"rewritten later\"}}]}");
        CodingAgentJob later = job();
        assertFalse("a justification changed after the audit is audited again",
                later.getNewTypeAudit().unaudited().isEmpty());
        assertTrue(later.getNewTypeRefusals().containsKey(PROGRESSION));
    }

    /**
     * Every top-level type of a refused file is withheld, not only the one the
     * file is named for, so a caller of a second type in that file is refused too.
     */
    @Test(timeout = 300000)
    public void secondTopLevelTypeOfRefusedFileIsWithheld() throws Exception {
        addBranchTypes();
        Path progression = repo.resolve(PROGRESSION);
        Files.writeString(progression, Files.readString(progression, StandardCharsets.UTF_8)
                + "\n/** One step of a progression. */\nfinal class ProgressionStep {\n}\n",
                StandardCharsets.UTF_8);
        String caller = SEQ + "StepCounter.java";
        write(caller, "package org.example.seq;\n\n/** Counts steps. */\n"
                + "public final class StepCounter {\n\tprivate final ProgressionStep s = null;\n}\n");
        Map<String, String> refusals = job().getNewTypeRefusals();
        assertTrue(refusals.toString(), refusals.containsKey(PROGRESSION));
        assertTrue(refusals.toString(), refusals.get(caller).contains("refers to org.example.seq.ProgressionStep"));
        assertFalse(refusals.toString(), refusals.containsKey(ROUTER));
    }

    /**
     * Every changed file that refers to a refused type is refused with it,
     * transitively, while files that do not refer to it are staged: a commit
     * never holds a caller without the type it calls.
     */
    @Test(timeout = 300000)
    public void callersOfRefusedTypeAreWithheld() throws Exception {
        addBranchTypes();
        String samePackage = SEQ + "ProgressionWalker.java";
        String imported = "mod/src/main/java/org/example/app/ProgressionReport.java";
        String transitive = "mod/src/main/java/org/example/app/ReportPrinter.java";
        String test = "mod/src/test/java/org/example/seq/IndexProgressionTest.java";
        String committed = "mod/src/main/java/org/example/misc/TaxBracket.java";
        write(samePackage, "package org.example.seq;\n\n/** Walks a progression. */\n"
                + "public final class ProgressionWalker {\n\tprivate final IndexProgression p = null;\n}\n");
        write(imported, "package org.example.app;\n\nimport org.example.seq.IndexProgression;\n\n"
                + "/** Reports on a progression. */\npublic final class ProgressionReport {\n"
                + "\tpublic String of(IndexProgression p) { return \"\"; }\n}\n");
        write(transitive, "package org.example.app;\n\n/** Prints reports. */\n"
                + "public final class ReportPrinter {\n\tprivate final ProgressionReport r = null;\n}\n");
        write(test, "package org.example.seq;\n\npublic class IndexProgressionTest {\n"
                + "\tIndexProgression subject;\n}\n");
        Path tax = repo.resolve(committed);
        Files.writeString(tax, Files.readString(tax, StandardCharsets.UTF_8)
                .replace("public int id()", "public int bracketId()"), StandardCharsets.UTF_8);

        Map<String, String> refusals = job().getNewTypeRefusals();
        assertTrue(refusals.toString(), refusals.get(PROGRESSION).contains("has no resolved verdict"));
        for (String dependent : List.of(samePackage, imported, test)) {
            assertTrue(dependent + " -> " + refusals, refusals.containsKey(dependent));
            assertTrue(refusals.get(dependent), refusals.get(dependent)
                    .contains("refers to org.example.seq.IndexProgression"));
        }
        assertTrue(refusals.toString(), refusals.get(transitive)
                .contains("refers to org.example.app.ProgressionReport"));
        assertFalse(refusals.toString(), refusals.containsKey(ROUTER));
        assertFalse(refusals.toString(), refusals.containsKey(committed));
        assertEquals(refusals.toString(), 5, refusals.size());
    }

    /** References are found by qualified name, or by simple name only where the name is visible. */
    @Test(timeout = 30000)
    public void firstReferencedHonoursPackageVisibility() {
        List<String> types = List.of("org.example.seq.IndexProgression");
        assertNull("another package without an import cannot see the simple name",
                referenced("package org.example.app;\nclass A { IndexProgression p; }", types));
        Assert.assertEquals("org.example.seq.IndexProgression",
                referenced("package org.example.app;\nimport org.example.seq.*;\n"
                        + "class A { IndexProgression p; }", types));
        Assert.assertEquals("org.example.seq.IndexProgression",
                referenced("package org.example.app;\n"
                        + "class A { org.example.seq.IndexProgression p; }", types));
        Assert.assertEquals("org.example.seq.IndexProgression",
                referenced("package org.example.seq;\nclass A { IndexProgression p; }", types));
        assertNull("a longer name that merely starts with the type's name is not a reference",
                referenced("package org.example.seq;\nclass A { IndexProgressionView v; }", types));
        assertNull("a subpackage wildcard import does not make the name visible",
                referenced("package org.example.app;\nimport org.example.seq.sub.*;\n"
                        + "class A { IndexProgression p; }", types));
        assertNull(referenced("package org.example.app;\nclass A { }", List.of()));
        Assert.assertEquals("org.example.seq.IndexProgression",
                new JavaSourceFile(PROGRESSION, "package org.example.seq;\nclass X { }").namedType());
        Assert.assertEquals("Loose", new JavaSourceFile("Loose.java", "class Loose { }").namedType());
        assertTrue(NewTypeAudit.Scan.none().declaredBy(PROGRESSION, null).isEmpty());
    }

    /** Returns the first of {@code types} that a source with {@code text} refers to. */
    private static String referenced(String text, List<String> types) {
        return new JavaSourceFile("A.java", text).firstReferenced(types);
    }

    /**
     * When the base branch cannot be resolved the working-tree scanner is not
     * trusted, so the audit fails closed and refuses every file it was to examine.
     */
    @Test(timeout = 300000)
    public void unresolvableBaseBranchFailsClosed() throws Exception {
        addBranchTypes();
        git("update-ref", "-d", "refs/remotes/origin/master");
        CodingAgentJob job = job();
        assertTrue(job.getNewTypeAudit().scan().isEmpty());
        Map<String, String> refusals = job.getNewTypeRefusals();
        Assert.assertEquals(refusals.toString(), List.of(PROGRESSION, ROUTER).stream().sorted().toList(),
                refusals.keySet().stream().sorted().toList());
        for (String reason : refusals.values()) {
            assertTrue(reason, reason.contains("the new-type audit could not run"));
            assertTrue(reason, reason.contains("origin/master"));
        }
        assertNotNull(job.getNewTypeAudit().getFailure());
        CodingAgentJobEvent event = CodingAgentJobEvent.forJob(job, null);
        Assert.assertEquals("a backstop that did not run is visible in the job outcome",
                JobCompletionEvent.Status.DEGRADED, event.getStatus());
        assertTrue(event.getErrorMessage(), event.getErrorMessage().contains("new-type audit could not run"));
    }

    /** A base-branch scanner that fails refuses every examined file instead of staging it. */
    @Test(timeout = 300000)
    public void failingScannerFailsClosed() throws Exception {
        Path scanner = repo.resolve(NewTypeAudit.SCANNER_PATH);
        String good = Files.readString(scanner, StandardCharsets.UTF_8);
        Files.writeString(scanner, "import sys\nsys.exit(3)\n", StandardCharsets.UTF_8);
        git("commit", "--quiet", "-am", "broken scanner");
        git("update-ref", "refs/remotes/origin/broken", "HEAD");
        Files.writeString(scanner, good, StandardCharsets.UTF_8);
        git("commit", "--quiet", "-am", "restore scanner");
        addBranchTypes();
        CodingAgentJob job = job();
        job.setBaseBranch("broken");
        Map<String, String> refusals = job.getNewTypeRefusals();
        assertEquals(refusals.toString(), 2, refusals.size());
        assertTrue(refusals.toString(), refusals.get(ROUTER).contains("scanner exited with 3"));
    }

    /** The audit runs the base branch's scanner, so editing the working-tree copy changes nothing. */
    @Test(timeout = 300000)
    public void workingTreeScannerEditsAreIgnored() throws Exception {
        addBranchTypes();
        Files.writeString(repo.resolve(NewTypeAudit.SCANNER_PATH),
                "import json\nprint(json.dumps({\"types\": [], \"added\": []}))\n", StandardCharsets.UTF_8);
        List<NewTypeAssessment> found = job().getNewTypeAudit().scan();
        assertEquals(describeAll(found), 1, found.size());
        Assert.assertEquals("org.example.seq.IndexProgression", found.get(0).getFqn());
    }

    /**
     * On the branch that introduces the scanner the base branch resolves but
     * has no copy, so the working-tree copy is run.
     */
    @Test(timeout = 300000)
    public void baseWithoutScannerRunsWorkingTreeCopy() throws Exception {
        git("rm", "--quiet", "--cached", NewTypeAudit.SCANNER_PATH);
        git("commit", "--quiet", "-m", "base without scanner");
        git("update-ref", "refs/remotes/origin/bare", "HEAD");
        git("add", NewTypeAudit.SCANNER_PATH);
        git("commit", "--quiet", "-m", "introduce scanner");
        addBranchTypes();
        CodingAgentJob job = job();
        job.setBaseBranch("bare");
        List<NewTypeAssessment> found = job.getNewTypeAudit().scan();
        assertEquals(describeAll(found), 1, found.size());
        Assert.assertEquals("org.example.seq.IndexProgression", found.get(0).getFqn());
    }

    /**
     * A type added to a file that already existed is reported as added even
     * when it resembles nothing, so the review still asks whether it should exist.
     */
    @Test(timeout = 300000)
    public void typeAddedToExistingFileIsReported() throws Exception {
        Path palette = repo.resolve("mod/src/main/java/org/example/misc/ColorPalette.java");
        Files.writeString(palette, Files.readString(palette, StandardCharsets.UTF_8)
                + "\n/** Counts packets dropped by a firewall. */\nfinal class DroppedPacketTally {\n}\n",
                StandardCharsets.UTF_8);
        NewTypeAudit.Scan scan = job().getNewTypeAudit().examine();
        assertFalse(scan.failed());
        Assert.assertEquals(List.of("org.example.misc.DroppedPacketTally"), scan.added());
        assertTrue(scan.assessments().isEmpty());
        assertFalse(ReviewPromptBuilder.newTypeSection(!scan.added().isEmpty(), scan.assessments()).isEmpty());
    }

    /** The scanner's JSON is parsed into assessments with their candidates. */
    @Test(timeout = 30000)
    public void parsesScannerOutput() throws IOException {
        String json = "{\"types\": [{\"name\": \"A\", \"fqn\": \"p.A\", \"path\": \"m/src/main/java/p/A.java\","
                + " \"candidates\": [{\"name\": \"B\", \"fqn\": \"p.B\", \"path\": \"m/src/main/java/p/B.java\","
                + " \"sentence\": \"A b.\", \"score\": 0.71, \"blocks\": true}], \"lift\": null,"
                + " \"justified\": false, \"gaps\": [\"no ledger entry\"], \"ledger\": null,"
                + " \"audit_verdict\": null}], \"added\": [\"p.A\", \"p.C\"],"
                + " \"declared\": {\"m/src/main/java/p/A.java\": [\"p.A\", \"p.AStep\"]}}";
        NewTypeAudit.Scan scan = NewTypeAudit.Scan.parse(json, List.of("m/src/main/java/p/A.java"));
        assertFalse(scan.failed());
        Assert.assertEquals(List.of("p.A", "p.C"), scan.added());
        Assert.assertEquals(List.of("m/src/main/java/p/A.java"), scan.files());
        List<NewTypeAssessment> parsed = scan.assessments();
        assertEquals(1, parsed.size());
        NewTypeAssessment a = parsed.get(0);
        Assert.assertEquals("p.A", a.getFqn());
        Assert.assertEquals("p.B", a.getCandidates().get(0).getFqn());
        assertEquals(0.71, a.getCandidates().get(0).getScore(), 1e-9);
        assertNull(a.getLedgerEntry());
        assertFalse(a.isKeptByAudit());
        Assert.assertEquals(List.of("no ledger entry"), a.getGaps());
        assertTrue(a.isDuplicate());
        assertTrue(a.describe().contains("duplicates p.B"));
        Assert.assertEquals(List.of("p.A", "p.AStep"), scan.declaredBy("m/src/main/java/p/A.java", null));
        Assert.assertEquals(List.of("p.D"), scan.declaredBy("m/src/main/java/p/D.java",
                new JavaSourceFile("m/src/main/java/p/D.java", "package p;\nclass D { }")));
    }

    /** Writes the two types the branch adds: a renamed equivalent and a new concept. */
    private void addBranchTypes() throws IOException {
        write(PROGRESSION, "package org.example.seq;\n\n"
                + "/** An arithmetic progression of index values. */\n"
                + "public final class IndexProgression {\n"
                + "\tpublic long valueAt(int pos) { return 0; }\n}\n");
        write(ROUTER, "package org.example.net;\n\n"
                + "/** Routes network packets between peers over a mesh. */\n"
                + "public final class PacketRouter {\n"
                + "\tpublic void route(byte[] packet) { }\n}\n");
    }

    /** Returns a job working in the temporary repository. */
    private CodingAgentJob job() {
        CodingAgentJob job = new CodingAgentJob("audit-test", "p");
        job.setWorkingDirectory(repo.toString());
        return job;
    }

    /** Returns the candidate names of an assessment. */
    private static List<String> candidateNames(NewTypeAssessment a) {
        return a.getCandidates().stream().map(NewTypeAssessment.Candidate::getFqn)
                .collect(Collectors.toList());
    }

    /** Describes assessments for assertion messages. */
    private static String describeAll(List<NewTypeAssessment> found) {
        return found.stream().map(NewTypeAssessment::describe).collect(Collectors.joining());
    }

    /** Locates the repository's own scanner by walking up from the working directory. */
    private static Path scannerSource() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File scanner = new File(dir, NewTypeAudit.SCANNER_PATH);
            if (scanner.isFile()) return scanner.toPath();
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("cannot find " + NewTypeAudit.SCANNER_PATH);
    }

    /** Writes a file into the temporary repository, creating its directories. */
    private void write(String rel, String text) throws IOException {
        Path path = repo.resolve(rel);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    /** Runs git in the temporary repository, failing on a non-zero exit. */
    private void git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException("git " + String.join(" ", args) + " failed: " + out);
    }
}
