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
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests the {@link ViolationClassification} value type: the agent-fixable
 * default carries no reason, an environment-determined classification carries
 * and exposes its reason, and the environment-determined factory rejects a
 * null or blank reason so the diagnostic contract {@link EnforcementRunner}
 * relies on cannot be broken.
 */
public class ViolationClassificationTest extends TestSuiteBase {

    /** The agent-fixable classification is a shared, reason-less flyweight. */
    @Test(timeout = 10000)
    public void agentFixableCarriesNoReasonAndIsShared() {
        ViolationClassification classification = ViolationClassification.agentFixable();
        assertFalse("Agent-fixable must not be environment-determined",
                classification.isEnvironmentDetermined());
        assertNull("Agent-fixable carries no reason", classification.getReason());
        assertSame("agentFixable() must return the shared instance",
                classification, ViolationClassification.agentFixable());
    }

    /** An environment-determined classification exposes exactly the reason it was given. */
    @Test(timeout = 10000)
    public void environmentDeterminedCarriesItsReason() {
        ViolationClassification classification =
                ViolationClassification.environmentDetermined("libfoo is not installed");
        assertTrue("Must report as environment-determined",
                classification.isEnvironmentDetermined());
        assertEquals("libfoo is not installed", classification.getReason());
    }

    /** A null reason is rejected: the retirement record would otherwise read "— null". */
    @Test(timeout = 10000)
    public void environmentDeterminedRejectsNullReason() {
        try {
            ViolationClassification.environmentDetermined(null);
            Assert.fail("A null reason must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue("The exception must explain the requirement: " + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("reason"));
        }
    }

    /** A blank reason is rejected: an environment-determined retirement must explain itself. */
    @Test(timeout = 10000)
    public void environmentDeterminedRejectsBlankReason() {
        try {
            ViolationClassification.environmentDetermined("   ");
            Assert.fail("A blank reason must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue("The exception must explain the requirement: " + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("reason"));
        }
    }
}
