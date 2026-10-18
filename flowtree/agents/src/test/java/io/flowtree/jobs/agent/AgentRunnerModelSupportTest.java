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

import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link AgentRunner#isModelSupported(String)} is the question a
 * submission-time validator asks of a runner. These tests pin the default
 * implementation every runner inherits unless it knows better: membership in
 * the advertised set, with an empty set meaning unconstrained.
 *
 * <p>{@link ClaudeCodeRunner} overrides it, and its own rule is covered in
 * {@link ClaudeCodeRunnerTest}.</p>
 */
public class AgentRunnerModelSupportTest extends TestSuiteBase {

    /**
     * A runner that advertises exactly {@code models} and does nothing else;
     * enough to exercise the interface default without a binary or a
     * subprocess.
     */
    private static final class AdvertisingRunner implements AgentRunner {

        /** The set this runner advertises through its capabilities. */
        private final Set<String> models;

        /**
         * Constructs a runner advertising {@code models}.
         *
         * @param models the advertised model set
         */
        private AdvertisingRunner(Set<String> models) {
            this.models = models;
        }

        @Override
        public String getName() {
            return "advertising-test-runner";
        }

        @Override
        public AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger) {
            throw new UnsupportedOperationException("not launched by this test");
        }

        @Override
        public AgentCapabilities capabilities() {
            return new AgentCapabilities(false, false, false, false, false, false, false, models);
        }
    }

    /** A runner that advertises a set accepts exactly that set. */
    @Test(timeout = 5000)
    public void anAdvertisedSetConstrainsByMembership() {
        AgentRunner runner = new AdvertisingRunner(Set.of("alpha", "beta"));
        assertTrue(runner.isModelSupported("alpha"));
        assertTrue(runner.isModelSupported("beta"));
        assertFalse(runner.isModelSupported("gamma"));
    }

    /**
     * An empty advertised set means unconstrained — the runner takes
     * whatever its provider takes, which is how opencode is configured.
     */
    @Test(timeout = 5000)
    public void anEmptyAdvertisedSetAcceptsAnything() {
        AgentRunner runner = new AdvertisingRunner(Set.of());
        assertTrue(runner.isModelSupported("anything-at-all"));
        assertTrue(new OpencodeRunner().isModelSupported("openrouter/some-new-model"));
    }

    /** No model is always accepted: the runner's own default applies. */
    @Test(timeout = 5000)
    public void noModelIsAlwaysAccepted() {
        AgentRunner constrained = new AdvertisingRunner(Set.of("alpha"));
        assertTrue(constrained.isModelSupported(null));
        assertTrue(constrained.isModelSupported(""));
    }
}
