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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Process-wide registry that maps short string names (e.g. {@code "claude"})
 * to {@link AgentRunner} factories.
 *
 * <p>The orchestrator looks up a runner per phase by name. {@code "claude"} is
 * pre-registered to a {@link ClaudeCodeRunner} supplier; later phases register
 * additional names. ServiceLoader would also work but is overkill for the
 * handful of runners we expect.</p>
 */
public final class AgentRunnerRegistry {

    /** Canonical name for the Claude Code runner. */
    public static final String CLAUDE = "claude";

    /** Canonical name for the opencode runner. */
    public static final String OPENCODE = "opencode";

    /** Map of registered runner names to their factory suppliers. */
    private static final Map<String, Supplier<AgentRunner>> FACTORIES =
            Collections.synchronizedMap(new LinkedHashMap<>());

    static {
        FACTORIES.put(CLAUDE, ClaudeCodeRunner::new);
        FACTORIES.put(OPENCODE, OpencodeRunner::new);
    }

    /** Static-only; not instantiable. */
    private AgentRunnerRegistry() {}

    /**
     * Registers (or replaces) a runner factory under {@code name}.
     *
     * @param name    short identifier used on the wire (e.g. {@code "opencode"})
     * @param factory supplier invoked each time a runner instance is requested
     * @throws IllegalArgumentException when {@code name} or {@code factory} is null
     */
    public static void register(String name, Supplier<AgentRunner> factory) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Runner name must not be null or empty");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Runner factory must not be null");
        }
        FACTORIES.put(name, factory);
    }

    /**
     * Returns the runner registered under {@code name}, instantiated via its
     * supplier.
     *
     * @param name the runner identifier
     * @return a fresh runner instance
     * @throws IllegalArgumentException when no runner is registered for {@code name}
     */
    public static AgentRunner get(String name) {
        Supplier<AgentRunner> factory = FACTORIES.get(name);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown agent runner: " + name
                    + " (registered: " + FACTORIES.keySet() + ")");
        }
        return factory.get();
    }

    /**
     * Returns the set of registered runner names.
     *
     * @return an unmodifiable snapshot of the registered names
     */
    public static Set<String> available() {
        synchronized (FACTORIES) {
            return Set.copyOf(FACTORIES.keySet());
        }
    }

    /**
     * Throws {@link IllegalArgumentException} when {@code name} is not
     * registered, with a message that includes the current registered names.
     *
     * <p>Callers that want to validate a name before storing it (e.g. job
     * setters) should use this method rather than duplicating the guard.</p>
     *
     * @param name the runner identifier to validate
     * @throws IllegalArgumentException when {@code name} is not registered
     */
    public static void validateName(String name) {
        Set<String> registered = available();
        if (!registered.contains(name)) {
            throw new IllegalArgumentException("Unknown agent runner: " + name
                    + " (registered: " + registered + ")");
        }
    }
}
