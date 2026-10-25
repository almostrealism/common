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
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link TransientGitException}'s self-identifying predicates, which
 * decide whether a terminated job's failure is reported as an infrastructure
 * hiccup ({@code DEGRADED}) rather than an agent failure ({@code FAILED}).
 */
public class TransientGitExceptionTest extends TestSuiteBase {

    /** A direct transient exception is recognised and described with its message. */
    @Test(timeout = 10000)
    public void recognisesADirectTransientFailure() {
        TransientGitException e = new TransientGitException("both pull and reset failed");
        assertTrue(TransientGitException.isTransient(e));
        assertTrue(TransientGitException.describe(e).contains("both pull and reset failed"));
        assertTrue("the label marks it as infrastructure, not an agent failure",
                TransientGitException.describe(e).startsWith("Transient git infrastructure failure:"));
    }

    /** A transient exception wrapped in another exception is still recognised through the cause chain. */
    @Test(timeout = 10000)
    public void recognisesAWrappedTransientFailure() {
        Exception wrapped = new RuntimeException("outer",
                new IllegalStateException(new TransientGitException("commit race")));
        assertTrue(TransientGitException.isTransient(wrapped));
        assertTrue(TransientGitException.describe(wrapped).contains("commit race"));
    }

    /** An ordinary agent failure is neither transient nor labelled as infrastructure. */
    @Test(timeout = 10000)
    public void doesNotMisclassifyAnOrdinaryFailure() {
        assertFalse(TransientGitException.isTransient(new IOException("agent logic failed")));
        assertFalse(TransientGitException.isTransient(null));
    }

    /** A transient exception propagates as an IOException so the git phases need no new checked type. */
    @Test(timeout = 10000)
    public void isAnIoException() {
        assertTrue(new TransientGitException("x") instanceof IOException);
        assertEquals("x", new TransientGitException("x").getMessage());
    }
}
