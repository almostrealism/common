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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the repository's type-equivalence search found for one new top-level
 * Java type: the existing types it likely duplicates, whether it builds on one
 * of them structurally, and what the justification ledger says about it.
 *
 * <p>Assessments are produced by {@code .claude/hooks/lib/type_equivalence.py}
 * (its {@code scan} command), the same search the new-type hook uses to block a
 * duplicate at the moment it is written, and parsed by {@link #parseAll(JsonNode)}.
 * Only types with at least one candidate are ever assessed.</p>
 *
 * @author Michael Murray
 * @see NewTypeAudit
 */
final class NewTypeAssessment {

    /** Verdict a type audit records in the ledger for a type it decided to keep. */
    static final String VERDICT_KEPT = "kept";

    /** Fully-qualified name of the new type. */
    private final String fqn;

    /** Repository-relative path of the file declaring it. */
    private final String path;

    /** Existing types the new type likely duplicates, best first. */
    private final List<Candidate> candidates;

    /** How the new type builds on a candidate, or {@code null} when it does not. */
    private final String lift;

    /** Whether the ledger justifies the type against every candidate. */
    private final boolean justified;

    /** What the ledger entry fails to justify; empty when {@link #justified}. */
    private final List<String> gaps;

    /** The ledger entry for the type as JSON text, or {@code null} when there is none. */
    private final String ledgerEntry;

    /** The audit verdict recorded in the ledger, or {@code null}. */
    private final String auditVerdict;

    /**
     * Creates an assessment.
     *
     * @param fqn          fully-qualified name of the new type
     * @param path         repository-relative path declaring it
     * @param candidates   likely equivalents, best first
     * @param lift         how the type builds on a candidate, or {@code null}
     * @param justified    whether the ledger covers every candidate
     * @param gaps         what the ledger fails to justify
     * @param ledgerEntry  the ledger entry as JSON text, or {@code null}
     * @param auditVerdict the audit verdict in the ledger, or {@code null}
     */
    NewTypeAssessment(String fqn, String path, List<Candidate> candidates, String lift,
                      boolean justified, List<String> gaps, String ledgerEntry,
                      String auditVerdict) {
        this.fqn = fqn;
        this.path = path;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.lift = lift;
        this.justified = justified;
        this.gaps = Collections.unmodifiableList(new ArrayList<>(gaps));
        this.ledgerEntry = ledgerEntry;
        this.auditVerdict = auditVerdict;
    }

    /** Returns the fully-qualified name of the new type. */
    String getFqn() { return fqn; }

    /** Returns the repository-relative path of the file declaring the type. */
    String getPath() { return path; }

    /** Returns the existing types the new type likely duplicates, best first. */
    List<Candidate> getCandidates() { return candidates; }

    /** Returns how the type builds on a candidate, or {@code null} when it does not. */
    String getLift() { return lift; }

    /** Returns whether the type extends, implements or delegates to a candidate. */
    boolean buildsOnCandidate() { return lift != null; }

    /** Returns whether the ledger justifies the type against every candidate. */
    boolean isJustified() { return justified; }

    /** Returns what the ledger entry fails to justify; empty when justified. */
    List<String> getGaps() { return gaps; }

    /** Returns the ledger entry as JSON text, or {@code null} when there is none. */
    String getLedgerEntry() { return ledgerEntry; }

    /** Returns whether the ledger records a {@link #VERDICT_KEPT} audit verdict. */
    boolean isKeptByAudit() { return VERDICT_KEPT.equals(auditVerdict); }

    /**
     * Parses the assessments in the JSON the scanner prints:
     * {@code {"types": [...]}}; see {@link NewTypeAudit.Scan#parse}.
     *
     * @param root the parsed standard output of the scanner
     * @return the assessments, in scanner order
     * @throws IOException when the tree is not the scanner's JSON
     */
    static List<NewTypeAssessment> parseAll(JsonNode root) throws IOException {
        List<NewTypeAssessment> out = new ArrayList<>();
        JsonNode types = root == null ? null : root.get("types");
        if (types == null || !types.isArray()) {
            throw new IOException("scanner output has no \"types\" array");
        }
        for (JsonNode t : types) {
            if (!t.isObject()) {
                throw new IOException("scanner output has a non-object type entry");
            }
            String fqn = requireText(t, "fqn");
            String path = requireText(t, "path");
            JsonNode candidatesNode = t.get("candidates");
            if (candidatesNode == null || !candidatesNode.isArray()) {
                throw new IOException("scanner output type " + fqn + " has no \"candidates\" array");
            }
            List<Candidate> candidates = new ArrayList<>();
            for (JsonNode c : candidatesNode) {
                if (!c.isObject()) {
                    throw new IOException("scanner output type " + fqn + " has a non-object candidate");
                }
                candidates.add(new Candidate(requireText(c, "fqn"), requireText(c, "path"),
                        c.path("score").asDouble(), c.path("sentence").asText("")));
            }
            List<String> gaps = new ArrayList<>();
            for (JsonNode g : t.path("gaps")) gaps.add(g.asText());
            JsonNode ledger = t.get("ledger");
            JsonNode audit = ledger == null ? null : ledger.get("audit");
            out.add(new NewTypeAssessment(fqn, path,
                    candidates, textOrNull(t.get("lift")), t.path("justified").asBoolean(false),
                    gaps, ledger == null || ledger.isNull() ? null : ledger.toPrettyString(),
                    audit == null ? null : textOrNull(audit.get("verdict"))));
        }
        return out;
    }

    /**
     * Returns the required text field {@code field} of {@code node}, failing
     * closed when it is absent, non-textual or blank: a malformed scanner entry
     * must not pass as a successful audit (see {@link NewTypeAudit.Scan#parse}).
     *
     * @param node  the JSON object
     * @param field the required field name
     * @return the field's text
     * @throws IOException when the field is absent, non-textual or blank
     */
    private static String requireText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("scanner output entry is missing a \"" + field + "\" string");
        }
        return value.asText();
    }

    /** Returns the node's text, or {@code null} for an absent or JSON-null node. */
    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * Describes the type and its candidates in the form every prompt and
     * staging message uses: one line for the type, one per candidate.
     *
     * @return the multi-line description, ending in a newline
     */
    String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(fqn).append("  (").append(path).append(")\n");
        for (Candidate c : candidates) {
            sb.append("      resembles ").append(c.getFqn())
              .append("  (").append(c.getPath()).append(", score ")
              .append(String.format("%.2f", c.getScore())).append(")\n");
            if (!c.getSentence().isEmpty()) {
                sb.append("        \"").append(c.getSentence()).append("\"\n");
            }
        }
        return sb.toString();
    }

    /**
     * Describes the type as {@link #describe()} does, followed by its ledger
     * justification -- presented as a claim to verify -- and what that
     * justification fails to cover. This is the form the type audit is given.
     *
     * @return the multi-line description, ending in a newline
     */
    String describeWithJustification() {
        StringBuilder sb = new StringBuilder(describe());
        if (ledgerEntry == null) {
            sb.append("      ledger justification: none\n");
            return sb.toString();
        }
        sb.append("      ledger justification (a CLAIM to verify, not a fact):\n");
        for (String line : ledgerEntry.split("\n")) {
            sb.append("        ").append(line).append('\n');
        }
        if (!gaps.isEmpty()) {
            sb.append("      the justification is incomplete:\n");
            for (String gap : gaps) sb.append("        - ").append(gap).append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns the staging-refusal reason for this type: why the harness will
     * not commit it yet and what settles it.
     *
     * @return the one-line reason
     */
    String refusalReason() {
        StringBuilder sb = new StringBuilder("new type ").append(fqn).append(" resembles ");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(candidates.get(i).getFqn());
        }
        sb.append(" and has no resolved verdict -- use, extend or improve the existing type,"
                + " or record a justification in " + NewTypeAudit.LEDGER_PATH
                + " for the type audit to verify");
        return sb.toString();
    }

    /**
     * An existing type a new type likely duplicates.
     */
    static final class Candidate {

        /** Fully-qualified name of the existing type. */
        private final String fqn;

        /** Repository-relative path of the file declaring it. */
        private final String path;

        /** Similarity score in [0, 1]. */
        private final double score;

        /** First sentence of the existing type's javadoc; empty when it has none. */
        private final String sentence;

        /**
         * Creates a candidate.
         *
         * @param fqn      fully-qualified name of the existing type
         * @param path     repository-relative path declaring it
         * @param score    similarity score in [0, 1]
         * @param sentence first sentence of its javadoc, or empty
         */
        Candidate(String fqn, String path, double score, String sentence) {
            this.fqn = fqn;
            this.path = path;
            this.score = score;
            this.sentence = sentence;
        }

        /** Returns the fully-qualified name of the existing type. */
        String getFqn() { return fqn; }

        /** Returns the repository-relative path of the file declaring it. */
        String getPath() { return path; }

        /** Returns the similarity score in [0, 1]. */
        double getScore() { return score; }

        /** Returns the first sentence of the existing type's javadoc, or empty. */
        String getSentence() { return sentence; }
    }
}
