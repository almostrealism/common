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
 * when ar-manager is part of the configuration it builds.
 */
public class McpConfigBuilderRequiredServersTest extends TestSuiteBase {

    /** With a URL and a token, ar-manager is the one required server. */
    @Test(timeout = 30000)
    public void arManagerIsRequiredWhenConfigured() {
        McpConfigBuilder builder = new McpConfigBuilder();
        builder.setArManagerUrl("http://ar-manager:8010");
        builder.setArManagerToken("armt_tmp_testtoken");

        assertEquals(Set.of("ar-manager"), builder.requiredServerNames());
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
}
