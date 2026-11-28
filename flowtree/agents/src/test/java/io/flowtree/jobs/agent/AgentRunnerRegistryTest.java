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

package io.flowtree.jobs.agent;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AgentRunnerRegistry#find(String)}: it yields the registered
 * runner when one can be obtained, and {@code null} — never an exception — for
 * every case in which a runner cannot be asked about its capabilities.
 */
public class AgentRunnerRegistryTest extends TestSuiteBase {

    /** A registered runner is returned as a fresh instance. */
    @Test(timeout = 5000)
    public void findReturnsRegisteredRunner() {
        AgentRunner claude = AgentRunnerRegistry.find(AgentRunnerRegistry.CLAUDE);
        assertNotNull(claude);
        assertTrue(claude instanceof ClaudeCodeRunner);
    }

    /** A {@code null}, empty or unregistered name yields {@code null}. */
    @Test(timeout = 5000)
    public void findReturnsNullForMissingName() {
        assertNull(AgentRunnerRegistry.find(null));
        assertNull(AgentRunnerRegistry.find(""));
        assertNull(AgentRunnerRegistry.find("agent-runner-registry-test-unregistered"));
    }

    /** A supplier that throws, or that yields {@code null}, yields {@code null}. */
    @Test(timeout = 5000)
    public void findReturnsNullWhenSupplierCannotProduceRunner() {
        String throwing = "agent-runner-registry-test-throwing";
        AgentRunnerRegistry.register(throwing, () -> {
            throw new IllegalStateException("no binary configured");
        });
        assertNull(AgentRunnerRegistry.find(throwing));

        String yieldsNull = "agent-runner-registry-test-null";
        AgentRunnerRegistry.register(yieldsNull, () -> null);
        assertNull(AgentRunnerRegistry.find(yieldsNull));
    }
}
