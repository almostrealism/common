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

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link McpConfigBuilder#requiredServerNames()} names ar-manager exactly
 * when ar-manager is part of the configuration it builds <em>and</em>
 * enforcement is on — which, by default, it is not (see that method).
 */
public class McpConfigBuilderRequiredServersTest extends TestSuiteBase {

    /**
     * With enforcement on, a URL and a token, ar-manager is the one required
     * server. The flag is set explicitly because enforcement is off by
     * default; what this test pins is <em>which</em> server is named, not
     * what the flag's states mean ({@link #explicitlyEnabledEnforcementRequiresArManager}
     * and {@link #explicitlyDisablingEnforcementRequiresNothing} pin those).
     */
    @Test(timeout = 30000)
    public void arManagerIsRequiredWhenConfigured() {
        String previous = System.getProperty("AR_REQUIRE_MCP_SERVERS");
        System.setProperty("AR_REQUIRE_MCP_SERVERS", "enabled");
        try {
            McpConfigBuilder builder = new McpConfigBuilder();
            builder.setArManagerUrl("http://ar-manager:8010");
            builder.setArManagerToken("armt_tmp_testtoken");

            assertEquals(Set.of("ar-manager"), builder.requiredServerNames());
        } finally {
            restoreProperty("AR_REQUIRE_MCP_SERVERS", previous);
        }
    }

    /** With no ar-manager configured, nothing is required. */
    @Test(timeout = 30000)
    public void nothingIsRequiredWithoutArManager() {
        assertTrue(new McpConfigBuilder().requiredServerNames().isEmpty());
    }

    /**
     * A URL without a token is the misconfiguration {@code buildMcpConfig}
     * already refuses; asking what is required must refuse the same way
     * rather than answer "nothing".
     */
    @Test(timeout = 30000)
    public void aUrlWithoutATokenIsStillAMisconfiguration() {
        McpConfigBuilder builder = new McpConfigBuilder();
        builder.setArManagerUrl("http://ar-manager:8010");
        try {
            builder.requiredServerNames();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("token"));
        }
    }

    /**
     * The same misconfiguration is reported with enforcement explicitly off.
     * Whether a token is missing is a fact about the configuration, not about
     * the flag, so the check must precede the flag's early return — otherwise
     * turning enforcement off would also silence an unrelated configuration
     * error, and the first symptom would be a session with no ar-manager at
     * all.
     */
    @Test(timeout = 30000)
    public void aUrlWithoutATokenIsAMisconfigurationEvenWithEnforcementOff() {
        String previous = System.getProperty("AR_REQUIRE_MCP_SERVERS");
        System.setProperty("AR_REQUIRE_MCP_SERVERS", "disabled");
        try {
            McpConfigBuilder builder = new McpConfigBuilder();
            builder.setArManagerUrl("http://ar-manager:8010");
            builder.requiredServerNames();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("token"));
        } finally {
            restoreProperty("AR_REQUIRE_MCP_SERVERS", previous);
        }
    }

    /**
     * With {@code AR_REQUIRE_MCP_SERVERS} left unset, enforcement is off:
     * nothing is required even though ar-manager is fully configured. The
     * property is cleared first so the case is exercised as the deployment
     * actually runs it, whatever value a prior test (or the environment) may
     * have left behind.
     */
    @Test(timeout = 30000)
    public void unsetEnforcementDefaultsToRequiringNothing() {
        String previous = System.getProperty("AR_REQUIRE_MCP_SERVERS");
        System.clearProperty("AR_REQUIRE_MCP_SERVERS");
        try {
            McpConfigBuilder builder = new McpConfigBuilder();
            builder.setArManagerUrl("http://ar-manager:8010");
            builder.setArManagerToken("armt_tmp_testtoken");

            assertTrue(builder.requiredServerNames().isEmpty());
        } finally {
            restoreProperty("AR_REQUIRE_MCP_SERVERS", previous);
        }
    }

    /**
     * {@code AR_REQUIRE_MCP_SERVERS=enabled} is what turns enforcement on:
     * the machinery is intact and reachable, it is simply not the default.
     */
    @Test(timeout = 30000)
    public void explicitlyEnabledEnforcementRequiresArManager() {
        String previous = System.getProperty("AR_REQUIRE_MCP_SERVERS");
        System.setProperty("AR_REQUIRE_MCP_SERVERS", "enabled");
        try {
            McpConfigBuilder builder = new McpConfigBuilder();
            builder.setArManagerUrl("http://ar-manager:8010");
            builder.setArManagerToken("armt_tmp_testtoken");

            assertEquals(Set.of("ar-manager"), builder.requiredServerNames());
        } finally {
            restoreProperty("AR_REQUIRE_MCP_SERVERS", previous);
        }
    }

    /**
     * {@code AR_REQUIRE_MCP_SERVERS=disabled} states the default
     * explicitly: nothing is required even though ar-manager is fully
     * configured.
     */
    @Test(timeout = 30000)
    public void explicitlyDisablingEnforcementRequiresNothing() {
        String previous = System.getProperty("AR_REQUIRE_MCP_SERVERS");
        System.setProperty("AR_REQUIRE_MCP_SERVERS", "disabled");
        try {
            McpConfigBuilder builder = new McpConfigBuilder();
            builder.setArManagerUrl("http://ar-manager:8010");
            builder.setArManagerToken("armt_tmp_testtoken");

            assertTrue(builder.requiredServerNames().isEmpty());
        } finally {
            restoreProperty("AR_REQUIRE_MCP_SERVERS", previous);
        }
    }

    /**
     * Restores a system property to its pre-test value: {@code previous}
     * ({@code null} when the property was unset before the test) rather
     * than always clearing it, so a test does not erase a value some other
     * caller (or the JVM's launch configuration) had deliberately set.
     *
     * @param key      the system property name
     * @param previous the value to restore, or {@code null} to clear it
     */
    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
