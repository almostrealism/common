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

package io.flowtree.submission;

import io.flowtree.jobs.CodingAgentJob;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests that the resource limits a submission asks for are held to the
 * workstream's: an over-limit request is clamped and reported, an in-limit one
 * is honoured.
 */
public class ResourceLimitOverridesTest extends TestSuiteBase {

    /**
     * Builds a workstream with the given limits and a factory seeded from it,
     * then applies a submission body.
     *
     * @param maxTurns     the workstream's turn limit
     * @param maxBudgetUsd the workstream's spend limit
     * @param body         the submission body
     * @return the overrides, applied to {@link #factory}
     */
    private ResourceLimitOverrides apply(int maxTurns, double maxBudgetUsd, String body) {
        Workstream workstream = new Workstream("C1", "w-limits");
        workstream.setMaxTurns(maxTurns);
        workstream.setMaxBudgetUsd(maxBudgetUsd);
        factory = workstream.newJobFactory("prompt", "feature/limits");
        ResourceLimitOverrides overrides = new ResourceLimitOverrides(body);
        overrides.applyTo(workstream, factory);
        return overrides;
    }

    /** The factory the most recent {@link #apply} configured. */
    private CodingAgentJob.Factory factory;

    /** Turn and spend overrides above the workstream's limits are clamped and reported. */
    @Test(timeout = 30000)
    public void overLimitCapsAreClampedAndReported() {
        ResourceLimitOverrides overrides = apply(200, 20.0, "{\"maxTurns\":5000,\"maxBudgetUsd\":75.5}");

        assertEquals(200, factory.getMaxTurns());
        assertEquals(20.0, factory.getMaxBudgetUsd(), 0.0);
        assertEquals("200", overrides.getClamped().get("maxTurns"));
        assertEquals("20.0", overrides.getClamped().get("maxBudgetUsd"));

        StringBuilder json = new StringBuilder("{\"ok\":true");
        overrides.appendClampedJson(json);
        assertTrue(json.toString(), json.toString().contains("\"clampedLimits\":{"));
    }

    /** Turn and spend overrides within the workstream's limits are honoured, and nothing is reported. */
    @Test(timeout = 30000)
    public void inLimitCapsAreHonoured() {
        ResourceLimitOverrides overrides = apply(200, 20.0, "{\"maxTurns\":75,\"maxBudgetUsd\":4.25}");

        assertEquals(75, factory.getMaxTurns());
        assertEquals(4.25, factory.getMaxBudgetUsd(), 0.0);
        assertTrue(overrides.getClamped().isEmpty());

        StringBuilder json = new StringBuilder("{\"ok\":true");
        overrides.appendClampedJson(json);
        assertEquals("{\"ok\":true", json.toString());
    }

    /** Without overrides the job keeps the workstream's limits. */
    @Test(timeout = 30000)
    public void absentOverridesKeepTheWorkstreamLimits() {
        apply(200, 20.0, "{\"prompt\":\"x\"}");
        assertEquals(200, factory.getMaxTurns());
        assertEquals(20.0, factory.getMaxBudgetUsd(), 0.0);
        assertNull(factory.getInactivityTimeoutSeconds());
    }

    /** A workstream ceiling that is not positive does not clamp anything. */
    @Test(timeout = 30000)
    public void nonPositiveCeilingIsNoCeiling() {
        ResourceLimitOverrides overrides = apply(0, -1.0, "{\"maxTurns\":5000,\"maxBudgetUsd\":75.0}");
        assertEquals(5000, factory.getMaxTurns());
        assertEquals(75.0, factory.getMaxBudgetUsd(), 0.0);
        assertTrue(overrides.getClamped().isEmpty());
    }

    /**
     * A silence budget is handed to the job factory as asked, short or long.
     * The runner's budget is its ceiling, which only the job's governor knows
     * when the job launches; {@code JobResourceLimitsTest} covers that clamp.
     */
    @Test(timeout = 30000)
    public void silenceBudgetIsHandedToTheJob() {
        ResourceLimitOverrides shorter = apply(200, 20.0, "{\"inactivityTimeoutSeconds\":300}");
        assertEquals(Integer.valueOf(300), factory.getInactivityTimeoutSeconds());
        assertTrue(shorter.getClamped().isEmpty());

        apply(200, 20.0, "{\"inactivityTimeoutSeconds\":36000}");
        assertEquals(Integer.valueOf(36000), factory.getInactivityTimeoutSeconds());
    }

    /** The clamped report cannot be altered by a caller, so the response always reflects what applyTo did. */
    @Test(timeout = 30000)
    public void clampedReportIsReadOnly() {
        ResourceLimitOverrides overrides = apply(200, 20.0, "{\"maxTurns\":5000}");
        try {
            overrides.getClamped().put("maxBudgetUsd", "1.0");
            fail("the clamped report must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, overrides.getClamped().size());
            assertEquals("200", overrides.getClamped().get("maxTurns"));
        }
    }

    /** A silence budget that is not positive is a validation error. */
    @Test(timeout = 30000)
    public void nonPositiveSilenceBudgetIsRejected() {
        assertEquals("inactivityTimeoutSeconds must be positive",
                new ResourceLimitOverrides("{\"inactivityTimeoutSeconds\":0}").validationError());
        assertNull(new ResourceLimitOverrides("{\"inactivityTimeoutSeconds\":60}").validationError());
        assertNull(new ResourceLimitOverrides("{}").validationError());
    }

    /**
     * A fractional turn cap or silence budget is refused rather than truncated:
     * {@code inactivityTimeoutSeconds:1.5} must not quietly become one second.
     */
    @Test(timeout = 30000)
    public void fractionalIntegerLimitsAreRejected() {
        assertEquals("inactivityTimeoutSeconds must be a whole number",
                new ResourceLimitOverrides("{\"inactivityTimeoutSeconds\":1.5}").validationError());
        assertEquals("inactivityTimeoutSeconds must be a whole number",
                new ResourceLimitOverrides("{\"inactivityTimeoutSeconds\":0.5}").validationError());
        assertEquals("maxTurns must be a whole number",
                new ResourceLimitOverrides("{\"maxTurns\":75.5}").validationError());
        assertNull(new ResourceLimitOverrides("{\"maxTurns\":75.0,\"inactivityTimeoutSeconds\":\"300\"}")
                .validationError());
    }

    /** An integer limit too large for an {@code int} is refused rather than saturated. */
    @Test(timeout = 30000)
    public void outOfRangeIntegerLimitsAreRejected() {
        assertEquals("maxTurns must be a whole number",
                new ResourceLimitOverrides("{\"maxTurns\":3000000000}").validationError());
        assertEquals("inactivityTimeoutSeconds must be a whole number",
                new ResourceLimitOverrides("{\"inactivityTimeoutSeconds\":1e12}").validationError());
    }

    /**
     * A non-finite spend cap is refused, so it can never reach the job or be
     * echoed into the response as an invalid JSON token, even when the
     * workstream has no spend ceiling to clamp it to.
     */
    @Test(timeout = 30000)
    public void nonFiniteBudgetIsRejected() {
        assertEquals("maxBudgetUsd must be a finite number",
                new ResourceLimitOverrides("{\"maxBudgetUsd\":\"Infinity\"}").validationError());
        assertEquals("maxBudgetUsd must be a finite number",
                new ResourceLimitOverrides("{\"maxBudgetUsd\":1e400}").validationError());
        assertEquals("maxBudgetUsd must be a finite number",
                new ResourceLimitOverrides("{\"maxBudgetUsd\":\"NaN\"}").validationError());
        assertNull(new ResourceLimitOverrides("{\"maxBudgetUsd\":12.5}").validationError());
    }

    /** A silence budget that is named but not numeric is refused. */
    @Test(timeout = 30000)
    public void nonNumericSilenceBudgetIsRejected() {
        assertEquals("inactivityTimeoutSeconds must be a whole number",
                new ResourceLimitOverrides("{\"inactivityTimeoutSeconds\":\"soon\"}").validationError());
    }

    /** Applying overrides that fail validation throws instead of configuring the job. */
    @Test(timeout = 30000)
    public void invalidOverridesAreNotApplied() {
        Workstream workstream = new Workstream("C1", "w-limits");
        workstream.setMaxBudgetUsd(0.0);
        CodingAgentJob.Factory unconfigured = workstream.newJobFactory("prompt", "feature/limits");
        double budgetBefore = unconfigured.getMaxBudgetUsd();
        try {
            new ResourceLimitOverrides("{\"maxBudgetUsd\":\"Infinity\"}").applyTo(workstream, unconfigured);
            fail("invalid overrides must not be applied");
        } catch (IllegalStateException expected) {
            assertEquals("maxBudgetUsd must be a finite number", expected.getMessage());
            assertEquals(budgetBefore, unconfigured.getMaxBudgetUsd(), 0.0);
        }
    }

    /**
     * A same-named key nested in an earlier field must not be read in place of
     * the top-level value: otherwise a request could hide a nested positive
     * budget to slip a non-positive top-level one past validation, or a nested
     * over-limit cap to steer clamping.
     */
    @Test(timeout = 30000)
    public void nestedKeysDoNotSpoofTheTopLevelLimits() {
        assertEquals("inactivityTimeoutSeconds must be positive",
                new ResourceLimitOverrides(
                        "{\"meta\":{\"inactivityTimeoutSeconds\":60},\"inactivityTimeoutSeconds\":0}")
                        .validationError());

        ResourceLimitOverrides overrides = apply(200, 20.0,
                "{\"meta\":{\"maxTurns\":5000,\"maxBudgetUsd\":99.0},"
                        + "\"maxTurns\":75,\"maxBudgetUsd\":4.25}");
        assertEquals(75, factory.getMaxTurns());
        assertEquals(4.25, factory.getMaxBudgetUsd(), 0.0);
        assertTrue(overrides.getClamped().isEmpty());
    }
}
