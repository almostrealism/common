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

package io.flowtree.controller;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link JobKind#classify}, the back-derivation of a job's kind from
 * the trigger reason and phase on its row.
 */
public class JobKindTest extends TestSuiteBase {

    /** A manual job with no phase is ordinary coding work. */
    @Test(timeout = 30000)
    public void manualNoPhaseIsCoding() {
        assertEquals(JobKind.CODING, JobKind.classify("manual", null));
        assertEquals(JobKind.CODING, JobKind.classify("manual", ""));
    }

    /** An auto-resolve trigger marks a CI review job. */
    @Test(timeout = 30000)
    public void autoResolveIsCiReview() {
        assertEquals(JobKind.CI_REVIEW, JobKind.classify("auto_resolve", null));
    }

    /** A review/enforcement phase marks a CI review job regardless of trigger. */
    @Test(timeout = 30000)
    public void reviewPhaseIsCiReview() {
        assertEquals(JobKind.CI_REVIEW, JobKind.classify("manual", "code-review"));
        assertEquals(JobKind.CI_REVIEW, JobKind.classify("manual", "enforcement"));
        assertEquals(JobKind.CI_REVIEW, JobKind.classify("completion_listener", "settled-ci"));
    }

    /** A planning phase marks a planning job. */
    @Test(timeout = 30000)
    public void planningPhaseIsPlanning() {
        assertEquals(JobKind.PLANNING, JobKind.classify("manual", "planning"));
    }

    /** A falsification / retrospective / QA phase marks a QA job, ahead of a review match. */
    @Test(timeout = 30000)
    public void qaPhaseIsQa() {
        assertEquals(JobKind.QA, JobKind.classify("manual", "falsification"));
        assertEquals(JobKind.QA, JobKind.classify("manual", "retrospective"));
    }

    /** The phase wins over the trigger: an auto-resolve job in a QA phase is QA. */
    @Test(timeout = 30000)
    public void phaseWinsOverTrigger() {
        assertEquals(JobKind.QA, JobKind.classify("auto_resolve", "falsification"));
    }

    /** With neither signal present, the kind is unknown rather than silently coding. */
    @Test(timeout = 30000)
    public void noSignalIsUnknown() {
        assertEquals(JobKind.UNKNOWN, JobKind.classify(null, null));
        assertEquals(JobKind.UNKNOWN, JobKind.classify("", ""));
    }

    /**
     * Classification is locale-independent: an upper-case {@code CI} phase still
     * matches the {@code ci} branch under the Turkish locale, whose default
     * lower-casing would turn {@code I} into a dotless {@code ı} and miss it.
     */
    @Test(timeout = 30000)
    public void classificationIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals(JobKind.CI_REVIEW, JobKind.classify("manual", "CI"));
            assertEquals(JobKind.CI_REVIEW, JobKind.classify("AUTO_RESOLVE", null));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
