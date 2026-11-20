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
    }

    /** Deletes the temporary repository. */
    @After
    public void tearDown() throws IOException {
        if (repo == null || !Files.exists(repo)) return;
        try (Stream<Path> walk = Files.walk(repo)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
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

    /** A new type that extends its candidate is assessed but never refused or audited. */
    @Test(timeout = 300000)
    public void typeBuildingOnCandidateIsNeitherAuditedNorRefused() throws Exception {
        write(PROGRESSION, "package org.example.seq;\n\n"
                + "/** An arithmetic progression of index values. */\n"
                + "public final class IndexProgression extends ArithmeticIndexSequence {\n}\n");
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
    @Test(timeout = 30000)
    public void refusalLiftsOnlyAfterAuditKeepsType() {
        NewTypeAssessment kept = assessment("org.example.seq.Kept", SEQ + "Kept.java", "kept");
        NewTypeAssessment unverdicted = assessment("org.example.seq.Plain", SEQ + "Plain.java", null);

        NewTypeAudit audit = job().getNewTypeAudit();
        Map<String, String> before = audit.refusals(List.of(kept, unverdicted));
        assertEquals("a verdict written before the audit ran must not count", 2, before.size());
        assertTrue(before.get(SEQ + "Kept.java").contains(CANDIDATE));
        assertTrue(before.get(SEQ + "Kept.java").contains(NewTypeAudit.LEDGER_PATH));

        audit.markAudited(List.of(kept, unverdicted));
        Map<String, String> after = audit.refusals(List.of(kept, unverdicted));
        assertNull("an audited type the audit kept may be staged", after.get(SEQ + "Kept.java"));
        assertNotNull("an audited type without the kept verdict stays refused",
                after.get(SEQ + "Plain.java"));
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
        assertTrue(job.getNewTypeAudit().isAudited("org.example.seq.IndexProgression"));
        assertTrue(job.getNewTypeRefusals().isEmpty());
        assertFalse("nothing is left for the type audit", rule.isViolated(job));
    }

    /** The scanner's JSON is parsed into assessments with their candidates. */
    @Test(timeout = 30000)
    public void parsesScannerOutput() throws IOException {
        String json = "{\"types\": [{\"name\": \"A\", \"fqn\": \"p.A\", \"path\": \"m/src/main/java/p/A.java\","
                + " \"candidates\": [{\"name\": \"B\", \"fqn\": \"p.B\", \"path\": \"m/src/main/java/p/B.java\","
                + " \"sentence\": \"A b.\", \"score\": 0.71}], \"lift\": null, \"justified\": false,"
                + " \"gaps\": [\"no ledger entry\"], \"ledger\": null, \"audit_verdict\": null}]}";
        List<NewTypeAssessment> parsed = NewTypeAssessment.parseAll(json);
        assertEquals(1, parsed.size());
        NewTypeAssessment a = parsed.get(0);
        Assert.assertEquals("p.A", a.getFqn());
        Assert.assertEquals("p.B", a.getCandidates().get(0).getFqn());
        assertEquals(0.71, a.getCandidates().get(0).getScore(), 1e-9);
        assertNull(a.getLedgerEntry());
        assertFalse(a.isKeptByAudit());
        Assert.assertEquals(List.of("no ledger entry"), a.getGaps());
        assertTrue(a.describe().contains("resembles p.B"));
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

    /** Returns an assessment of a type resembling {@link #CANDIDATE}, with the given ledger verdict. */
    private static NewTypeAssessment assessment(String fqn, String path, String verdict) {
        return new NewTypeAssessment(fqn, path,
                List.of(new NewTypeAssessment.Candidate(CANDIDATE, SEQ + "ArithmeticIndexSequence.java",
                        0.6, "An index sequence.")),
                null, false, List.of(), verdict == null ? null : "{}", verdict);
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
